package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID

object PocketUpdates {
    private val prefs get()=Pocket.context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    var status by mutableStateOf("");private set
    var busy by mutableStateOf(false);private set
    var ready by mutableStateOf(false);private set
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    internal fun installedSigner(c:Context):String {
        val p=c.packageManager.getPackageInfo(c.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures=p.signingInfo?.apkContentsSigners?:throw IllegalStateException("Package signer unavailable")
        require(signatures.size==1){"Package signer unavailable"}
        return hash(signatures.single().toByteArray())
    }
    internal fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val bytes=ByteArray(65536);while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    internal fun file(c:Context,m:JSONObject)=File(c.cacheDir,"updates/nextcomp-${m.optLong("versionCode")}-${m.s("sha256").take(16)}.apk")
    internal fun file(c:Context)=rawStage()?.let{file(c,it)}?:File(c.cacheDir,"updates/nextcomp.apk")
    private fun rawStage()=prefs.getString("stage",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    private fun readyMetadata():JSONObject? {
        val m=rawStage()?:return null
        return if(UpdateStagePolicy.ready(m.optLong("versionCode"),BuildConfig.VERSION_CODE.toLong(),m.optBoolean("verified"),file(Pocket.context,m).exists(),file(Pocket.context,m).length(),m.optLong("size"),m.s("signerSha256"),installedSigner(Pocket.context)))m else null
    }
    internal fun hasNewerStage(candidate:JSONObject)=readyMetadata()?.let{!UpdateStagePolicy.replace(it.optLong("versionCode"),candidate.optLong("versionCode"))}?:false
    internal fun verify(c:Context,file:File,m:JSONObject){
        UpdatePackagePolicy.validate(m.optLong("versionCode"),BuildConfig.VERSION_CODE.toLong(),m.optLong("size"),m.s("sha256"),m.s("signerSha256"),installedSigner(c),m.s("apkUrl"),m.s("packageName"),c.packageName)
        require(file.length()==m.optLong("size")&&sha(file)==m.s("sha256")){"Update checksum does not match"}
        val archive=c.packageManager.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:throw IllegalStateException("Update package unavailable")
        val signers=archive.signingInfo?.apkContentsSigners?.map{hash(it.toByteArray())}?.toSet()?:emptySet()
        UpdatePackagePolicy.validateArchive(archive.packageName,c.packageName,archive.longVersionCode,m.optLong("versionCode"),signers,m.s("signerSha256"))
    }
    fun restore(){
        val staged=readyMetadata()
        ready=staged!=null
        if(!ready)prefs.edit().remove("stage").apply()
        busy=prefs.all.keys.any{it.startsWith("update-job:")}
        status=if(ready)"${staged!!.s("versionName")} is ready to install" else prefs.getString("status","").orEmpty().let{if(it.contains("is ready to install"))"" else it}
    }
    internal fun report(text:String,working:Boolean=false,isReady:Boolean=false){
        prefs.edit().putString("status",text).apply()
        Pocket.scope.launch{
            val staged=readyMetadata()
            ready=staged!=null;busy=working
            status=if(staged!=null&&!isReady&&!working){
                "${staged.s("versionName")} is ready to install"+if(text.contains("could not")||text.startsWith("Waiting"))" · $text" else ""
            }else text
        }
    }
    @Synchronized internal fun stage(metadata:JSONObject,partial:File){
        if(hasNewerStage(metadata)){partial.delete();report("Existing update is ready to install");return}
        val target=file(Pocket.context,metadata)
        require(partial.renameTo(target)){"Unable to save update"}
        val saved=JSONObject(metadata.toString()).put("verified",true)
        prefs.edit().putString("stage",saved.toString()).commit()
        report("${saved.s("versionName")} is ready to install",isReady=true);notifyReady(saved)
        // Keep one preceding immutable APK for an installer already opened on that URI.
        target.parentFile!!.listFiles()?.filter{it.extension=="apk"&&it!=target}?.sortedByDescending{it.lastModified()}?.drop(1)?.forEach{it.delete()}
    }
    fun check(download:Boolean=false,local:Boolean=if(Pocket.savedToken(false).isNotBlank())false else Pocket.local,force:Boolean=false){
        val base=Pocket.savedBase(local);val token=Pocket.savedToken(local)
        if(base.isBlank()||token.isBlank()){report("Connect a paired workstation to check for updates");return}
        val checkedKey="last-check:$local:$base"
        if(!force&&System.currentTimeMillis()-prefs.getLong(checkedKey,0)<3600000)return
        prefs.edit().putLong(checkedKey,System.currentTimeMillis()).apply()
        enqueueCaptured(JSONObject().put("local",local).put("base",base).put("token",token).put("download",download))
    }
    @Synchronized private fun enqueueCaptured(payload:JSONObject){
        if(prefs.all.keys.count{it.startsWith("update-job:")}>=16){
            // Keep the newest overflow request; completion drains it into the serial queue.
            prefs.edit().putString("update-overflow",payload.toString()).commit();return
        }
        val key="update-job:"+UUID.randomUUID()
        prefs.edit().putString(key,payload.toString()).commit()
        report(if(payload.optBoolean("download"))"Checking for an update…" else "Checking…",working=true)
        val work=OneTimeWorkRequestBuilder<UpdateDownloadWorker>().setInputData(workDataOf("key" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(Pocket.context).enqueueUniqueWork("nextcomp-app-updates",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
    }
    fun offer(local:Boolean=false){check(download=true,local=local,force=true)}
    fun installOrCheck(c:Context){restore();if(ready)install(c)else check(download=true,force=true)}
    internal fun job(key:String)=prefs.getString(key,null)?.let{JSONObject(it)}
    @Synchronized internal fun finishJob(key:String){
        prefs.edit().remove(key).commit()
        val overflow=prefs.getString("update-overflow",null)
        if(overflow!=null&&prefs.all.keys.count{it.startsWith("update-job:")}<16){
            prefs.edit().remove("update-overflow").commit();enqueueCaptured(JSONObject(overflow))
        }
    }
    private fun notifyReady(metadata:JSONObject){
        val c=Pocket.context
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("app-updates","NextComp app updates",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(c,MainActivity::class.java).putExtra("appUpdate",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(c,1191,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(c,"app-updates").setSmallIcon(R.drawable.ic_notification).setContentTitle("NextComp update ready")
            .setContentText("${metadata.s("versionName")} · Tap to install").setContentIntent(pending).addAction(R.drawable.ic_notification,"Install",pending).setAutoCancel(true).build()
        try{NotificationManagerCompat.from(c).notify("nextcomp-update",1191,notification)}catch(_:SecurityException){}
    }
    fun install(c:Context){
        val raw=prefs.getString("stage",null)?:return
        Pocket.scope.launch{
            try{
                val m=JSONObject(raw);val apk=file(c,m)
                withContext(Dispatchers.IO){verify(c,apk,m)}
                if(!c.packageManager.canRequestPackageInstalls()){
                    report("Allow NextComp to install updates, then tap Install again",isReady=true)
                    c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return@launch
                }
                val uri=FileProvider.getUriForFile(c,c.packageName+".files",apk)
                c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                report("Confirm Install in Android to finish the update",isReady=true)
            }catch(_:Exception){prefs.edit().remove("stage").apply();report("The update could not be verified. Check again.")}
        }
    }
}

@Composable fun PocketUpdateControl(){
    val c=androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit){PocketUpdates.restore()}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("App updates",color=Paper,style=MaterialTheme.typography.titleSmall)
        Text(PocketUpdates.status.ifBlank{"NextComp ${BuildConfig.VERSION_NAME}"},color=Muted,style=MaterialTheme.typography.bodySmall)
        Button(onClick={if(PocketUpdates.ready)PocketUpdates.install(c)else PocketUpdates.check(download=true,force=true)},enabled=PocketUpdates.ready||!PocketUpdates.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Text(if(PocketUpdates.ready)"Install update" else if(PocketUpdates.busy)"Checking update…" else "Check for updates")
        }
    }
}
