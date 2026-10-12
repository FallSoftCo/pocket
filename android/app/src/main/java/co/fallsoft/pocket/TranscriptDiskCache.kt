package co.fallsoft.pocket

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun transcriptCacheOwner(environment:String,base:String,token:String)=backendOwner(environment+"\u0000"+base,token)

/** Private, disposable presentation cache; never an outgoing-command or read-ack journal. */
internal class TranscriptDiskCache(private val directory:File,private val maxEntryBytes:Long=32L*1024*1024,private val maxTotalBytes:Long=128L*1024*1024){
 private val positionEpoch=java.util.UUID.randomUUID().toString()
 private val maxPositionBytes=16L*1024
 private fun path(scope:String,thread:String):File{
  require(scope.matches(Regex("[a-f0-9]{64}")))
  val id=MessageDigest.getInstance("SHA-256").digest(thread.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
  return File(directory,"$scope-$id.json")
 }
 @Synchronized fun read(scope:String,thread:String):String?{
  val file=path(scope,thread)
  if(!file.isFile||file.length()>maxEntryBytes)return null
  return file.readText().also{file.setLastModified(System.currentTimeMillis())}
 }
 @Synchronized fun write(scope:String,thread:String,text:String,stillCurrent:()->Boolean={true}):Boolean{
  val bytes=text.toByteArray();if(bytes.size>maxEntryBytes||!stillCurrent())return false
  directory.mkdirs();val file=path(scope,thread);val temporary=File(directory,file.name+".tmp")
  try{
   temporary.outputStream().use{it.write(bytes);it.flush();(it as java.io.FileOutputStream).fd.sync()}
   if(!stillCurrent())return false
   Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
   prune(file)
   return true
  }finally{temporary.delete()}
 }
 /** Tiny independently ordered presentation checkpoint. A delayed full snapshot
  * cannot overwrite a newer scroll position in this cache-owner lifetime. */
 @Synchronized fun writePosition(scope:String,thread:String,position:String,sequence:Long):Boolean{
  require(sequence>=0)
  val body=runCatching{org.json.JSONObject(position)}.getOrNull()?:return false
  val base=path(scope,thread);val file=File(directory,base.name+".position")
  val previous=positionRecord(file)
  if(previous?.s("epoch")==positionEpoch&&previous.optLong("sequence")>=sequence)return false
  val bytes=org.json.JSONObject().put("schema",1).put("epoch",positionEpoch).put("sequence",sequence).put("position",body).toString().toByteArray()
  if(bytes.size>maxPositionBytes)return false
  directory.mkdirs();val temporary=File(directory,file.name+".tmp")
  try{
   temporary.outputStream().use{it.write(bytes);it.flush();(it as java.io.FileOutputStream).fd.sync()}
   Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
   if(base.isFile)base.setLastModified(System.currentTimeMillis())
   prune(base);return true
  }finally{temporary.delete()}
 }
 private fun positionRecord(file:File):org.json.JSONObject?=runCatching{
  if(!file.isFile||file.length()>maxPositionBytes)return null
  org.json.JSONObject(file.readText()).takeIf{it.optInt("schema")==1&&it.optJSONObject("position")!=null}
 }.getOrNull()
 @Synchronized fun readPosition(scope:String,thread:String):String? =
  positionRecord(File(directory,path(scope,thread).name+".position"))?.optJSONObject("position")?.toString()
 private fun prune(protected:File){
  val files=directory.listFiles()?.filter{it.extension in listOf("json","position")}.orEmpty()
  var size=files.sumOf{it.length()}
  // Evict a whole inactive checkpoint, including its small position sidecar.
  val groups=files.groupBy{if(it.extension=="position")File(directory,it.name.removeSuffix(".position"))else it}
  groups.filterKeys{it!=protected}.entries.sortedBy{entry->if(entry.key.isFile)entry.key.lastModified()else entry.value.maxOf{it.lastModified()}}.forEach{entry->
   if(size>maxTotalBytes)entry.value.forEach{file->val bytes=file.length();if(file.delete())size-=bytes}
  }
 }

}

/** Persist labels and saved excerpts, not stale execution settings or pending authority. */
internal fun transcriptPresentationDetail(id:String,detail:org.json.JSONObject):org.json.JSONObject{
 val thread=detail.optJSONObject("thread")
 return org.json.JSONObject().put("thread",org.json.JSONObject().put("id",id).put("name",thread?.s("name").orEmpty()).put("cwd",thread?.s("cwd").orEmpty())).put("notes",detail.optJSONArray("notes"))
}
