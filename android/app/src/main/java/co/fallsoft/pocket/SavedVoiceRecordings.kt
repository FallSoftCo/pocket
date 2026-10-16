package co.fallsoft.pocket

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Preserves a stranded turn independently from the next capture. Never submits it. */
internal object SavedVoiceRecordings {
    private fun directory(c:Context)=File(c.noBackupFilesDir,"saved-voice-recordings").apply{check(exists()||mkdirs())}
    private fun key(id:String)=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun syncDirectory(file:File){val fd=android.system.Os.open(file.absolutePath,android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}}
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").let{hash->file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}};hash.digest().toList()}
    private fun write(file:File,bytes:ByteArray){FileOutputStream(file).use{it.write(bytes);it.fd.sync()}}
    @Synchronized fun preserve(c:Context,prefs:SharedPreferences):Boolean {
        val id=prefs.getString("voicePendingId",null)?:return false
        val root=directory(c);val final=File(root,key(id));val temp=File(root,key(id)+".preparing")
        val data=JSONObject().put("id",id).put("savedAt",System.currentTimeMillis()).put("state","Status not checked")
        val keys=prefs.all.keys.filter{it.startsWith("voicePending")}.sorted()
        keys.forEach{key->data.put(key,prefs.all[key])}
        val wav=File(c.filesDir,"voice-pending.wav")
        if(!final.exists()){
            check(!temp.exists()||temp.deleteRecursively()){ "Could not prepare saved recording" }
            check(temp.mkdir())
            if(wav.exists()){FileOutputStream(File(temp,"recording.wav")).use{out->wav.inputStream().use{it.copyTo(out)};out.fd.sync()}}
            write(File(temp,"record.json"),data.toString().toByteArray())
            syncDirectory(temp)
            check(temp.renameTo(final)){"Could not preserve saved recording"}
        }
        syncDirectory(root)
        val saved=JSONObject(File(final,"record.json").readText())
        check(saved.s("id")==id&&saved.s("voicePendingOwner")==data.s("voicePendingOwner"))
        keys.forEach{check(saved.opt(it).toString()==data.opt(it).toString()){ "Saved recording identity changed" }}
        if(wav.exists())check(File(final,"recording.wav").isFile&&digest(File(final,"recording.wav"))==digest(wav))
        check(prefs.getString("voicePendingId",null)==id)
        check(prefs.edit().apply{keys.forEach{remove(it)}}.commit()){ "Could not release saved recording slot" }
        return true
    }
    fun list(c:Context):List<JSONObject> = directory(c).listFiles().orEmpty().filter{!it.name.endsWith(".preparing")}.mapNotNull{runCatching{JSONObject(File(it,"record.json").readText())}.getOrNull()}.sortedByDescending{it.optLong("savedAt")}
    fun update(c:Context,id:String,result:JSONObject){
        val folder=File(directory(c),key(id));val file=File(folder,"record.json");val old=JSONObject(file.readText());check(old.s("id")==id)
        old.put("state",result.s("state","Status unavailable")).put("result",result).put("checkedAt",System.currentTimeMillis())
        val temp=File(folder,"record.next");write(temp,old.toString().toByteArray());check(temp.renameTo(file));syncDirectory(folder)
    }
}
