package co.fallsoft.pocket

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class UpdateDownloadWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        val key=inputData.getString("key")?:return@withContext Result.success()
        val job=PocketUpdates.job(key)?:return@withContext Result.success()
        val local=job.optBoolean("local");val environment=job.s("environment",if(local)"phone" else "workstation");val base=job.s("base").trimEnd('/');val token=job.s("token")
        fun current()=Pocket.savedBase(local,environment).trimEnd('/')==base&&Pocket.savedToken(local,environment)==token
        if(!current()){PocketUpdates.finishJob(key);return@withContext Result.success()}
        val http=Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val signer=PocketUpdates.installedSigner(applicationContext)
        val query="?signer=$signer&versionCode=${BuildConfig.VERSION_CODE}"
        val partial=java.io.File(applicationContext.cacheDir,"updates/${key.substringAfter(':')}.partial")
        try{
            val metadata=http.newCall(Request.Builder().url(base+"/api/app/update"+query).header("Authorization","Bearer $token").build()).execute().use{r->
                if(r.code>=500||r.code==408||r.code==429)throw IOException("Update server unavailable")
                require(r.isSuccessful){"Update server unavailable"};JSONObject(r.body?.string()?:"{}")
            }
            if(!metadata.optBoolean("available")){
                PocketUpdates.report(if(metadata.s("reason")=="unsupported_signer")"No compatible update is available for this installation" else "NextComp is up to date");PocketUpdates.finishJob(key);return@withContext Result.success()
            }
            UpdatePackagePolicy.validate(metadata.optLong("versionCode"),BuildConfig.VERSION_CODE.toLong(),metadata.optLong("size"),metadata.s("sha256"),metadata.s("signerSha256"),signer,metadata.s("apkUrl"),metadata.s("packageName"),applicationContext.packageName)
            if(!job.optBoolean("download")){PocketUpdates.report("${metadata.s("versionName")} is available");PocketUpdates.finishJob(key);return@withContext Result.success()}
            if(PocketUpdates.hasNewerStage(metadata)){PocketUpdates.report("Existing update is ready to install");PocketUpdates.finishJob(key);return@withContext Result.success()}
            PocketUpdates.report("Downloading ${metadata.s("versionName")}…",working=true)
            partial.parentFile!!.mkdirs()
            http.newCall(Request.Builder().url(base+metadata.s("apkUrl")+query).header("Authorization","Bearer $token").build()).execute().use{r->
                if(r.code>=500||r.code==408||r.code==429)throw IOException("Download unavailable")
                require(r.isSuccessful){"Download unavailable"}
                val body=r.body?:throw IOException("Empty download")
                require(body.contentLength()<=UpdatePackagePolicy.MAX_BYTES){"Update is too large"}
                var total=0L
                body.byteStream().use{input->partial.outputStream().use{output->val bytes=ByteArray(65536);while(true){val count=input.read(bytes);if(count<0)break;total+=count;require(total<=metadata.optLong("size")){"Download size does not match"};output.write(bytes,0,count)}}}
            }
            if(!current())throw IllegalStateException("Pairing changed during download")
            PocketUpdates.verify(applicationContext,partial,metadata)
            PocketUpdates.stage(metadata,partial);PocketUpdates.finishJob(key);Result.success()
        }catch(e:CancellationException){throw e}
        catch(_:IOException){partial.delete();PocketUpdates.report("Waiting to retry the update download");Result.retry()}
        catch(_:Exception){partial.delete();PocketUpdates.report("The update could not be verified. Check again.");PocketUpdates.finishJob(key);Result.success()}
    }
}
