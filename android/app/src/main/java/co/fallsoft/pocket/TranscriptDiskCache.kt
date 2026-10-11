package co.fallsoft.pocket

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun transcriptCacheOwner(environment:String,base:String,token:String)=backendOwner(environment+"\u0000"+base,token)

/** Private, disposable presentation cache; never an outgoing-command or read-ack journal. */
internal class TranscriptDiskCache(private val directory:File,private val maxEntryBytes:Long=32L*1024*1024,private val maxTotalBytes:Long=128L*1024*1024){
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
   var size=directory.listFiles()?.filter{it.extension=="json"}?.sumOf{it.length()}?:0
   directory.listFiles()?.filter{it.extension=="json"&&it!=file}?.sortedBy{it.lastModified()}?.forEach{old->if(size>maxTotalBytes){val length=old.length();if(old.delete())size-=length}}
   return true
  }finally{temporary.delete()}
 }
}
