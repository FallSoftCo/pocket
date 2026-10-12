package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationFilterNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device=UiDevice.getInstance(i)
 private fun main(block:()->Unit)=i.runOnMainSync(block)
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue(condition())}
 private fun visiblePassages(prefix:String):List<Pair<String,android.graphics.Rect>>{
  val out=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(out)
  val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.ByteArrayInputStream(out.toByteArray()))
  val nodes=doc.getElementsByTagName("node")
  return (0 until nodes.length).mapNotNull{n->val e=nodes.item(n) as org.w3c.dom.Element;val text=e.getAttribute("text");val b=Regex("[0-9]+").findAll(e.getAttribute("bounds")).map{it.value.toInt()}.toList();if(!text.startsWith(prefix)||b.size!=4)null else text to android.graphics.Rect(b[0],b[1],b[2],b[3])}.filter{!it.second.isEmpty}.sortedBy{it.second.top}
 }
 @Test fun filterKeepsVisibleAnswerWhenLeadingCommentaryIsRemoved(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putBoolean("diagnostic:scroll",true).commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","filter-reading").put("version",100+n).put("row",JSONObject().put("id","filter-passage-$n").put("turnId","filter-reading").put("kind","message").put("phase",if(n%2==0)"final_answer" else "commentary").put("text","Filter passage $n: a meaningful result that remains readable while commentary is filtered away. This extra sentence gives the passage enough height to expose position changes.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Filter passage 39:")),5000));Thread.sleep(300)
  fun passages()=visiblePassages("Filter passage ")
  var target:String?=null;var top=0
  for(attempt in 0 until 35){
   val visible=passages();val first=visible.firstOrNull();val number=first?.first?.substringAfter("Filter passage ")?.substringBefore(":")?.toIntOrNull()
   println("Filter position $attempt: "+visible.map{it.first.substringBefore(":")+"@"+it.second})
   val answer=visible.firstOrNull{it.first.substringAfter("Filter passage ").substringBefore(":").toIntOrNull()?.rem(2)==0&&it.second.top>180&&it.second.bottom<device.displayHeight*3/4}
   if(number!=null&&number%2==1&&answer!=null){target=answer.first;top=answer.second.top;break}
   requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.2f);Thread.sleep(180)
  }
  device.dumpWindowHierarchy(java.io.File(context.cacheDir,"filter-leading-commentary-before.xml"))
  val text=requireNotNull(target){"Could not position commentary before a visible answer"}
  assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"filter-leading-commentary-before.png")))
  fun ink(file:String,bounds:android.graphics.Rect):Int{
   val bitmap=android.graphics.BitmapFactory.decodeFile(java.io.File(context.cacheDir,file).path)
   try{var count=0;for(y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height))for(x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)){val pixel=bitmap.getPixel(x,y);if(android.graphics.Color.red(pixel)>150&&android.graphics.Color.green(pixel)>150&&android.graphics.Color.blue(pixel)>150)count++};return count}finally{bitmap.recycle()}
  }
  val textBounds=passages().first{it.first==text}.second
  val beforeInk=ink("filter-leading-commentary-before.png",textBounds)
  device.findObject(By.text("Important")).click();Thread.sleep(500)
  assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"filter-leading-commentary-important.png")))
  val afterInk=ink("filter-leading-commentary-important.png",textBounds)
  println("Filter visible text ink: $beforeInk -> $afterInk")
  assertTrue("Filtering left semantically present text visually blank: $beforeInk -> $afterInk",beforeInk>0&&afterInk>=beforeInk*.7)
  val answer=device.findObject(By.text(text));assertNotNull("Filter hid visible answer",answer)
  assertTrue("Removing leading commentary moved answer from $top to ${answer.visibleBounds.top}",kotlin.math.abs(answer.visibleBounds.top-top)<=24)
  device.findObject(By.text("Important")).click();Thread.sleep(500)
  assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"filter-leading-commentary-restored.png")))
  assertTrue("Unfilter shifted visible answer",kotlin.math.abs(device.findObject(By.text(text)).visibleBounds.top-top)<=24)
 }
 @Test fun filterPreservesVisiblePassageAndReturnsWithoutJumping(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{
   for(n in 0..29){
    val row=JSONObject().put("id","passage-$n").put("turnId","passages").put("kind","message").put("phase",if(n%2==0)"final_answer" else "commentary").put("text","Passage $n: a meaningful synthetic result with enough text to occupy a normal conversation row and test reading position.")
    PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","passages").put("version",100+n).put("row",row))
   }
  }
  assertTrue(device.wait(Until.hasObject(By.textContains("Passage 29:")),5000))
  // Read older work, using the same vertical gesture as one-handed scrolling.
  repeat(3){device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*2/3,25);Thread.sleep(250)}
  fun visibleFinal()=visiblePassages("Passage ").firstOrNull{it.first.substringAfter("Passage ").substringBefore(":").toIntOrNull()?.rem(2)==0&&it.second.top>50&&it.second.bottom<device.displayHeight*3/4}
  val passage=requireNotNull(visibleFinal()){"No complete final passage visible after scroll"}
  val text=passage.first;val top=passage.second.top
  device.findObject(By.text("Important")).click();Thread.sleep(400)
  val filtered=device.findObject(By.text(text));assertNotNull("Important filter lost reading passage",filtered)
  assertTrue("Important filter moved passage from $top to ${filtered.visibleBounds.top}",kotlin.math.abs(filtered.visibleBounds.top-top)<=24)
  device.findObject(By.text("Important")).click();Thread.sleep(400)
  val restored=device.findObject(By.text(text));assertNotNull("Unfilter lost reading passage",restored)
  assertTrue("Unfilter moved reading passage",kotlin.math.abs(restored.visibleBounds.top-top)<=24)
  fun end(status:String)=main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","states").put("version",500+listOf("failed","interrupted","completed").indexOf(status)).put("turn",JSONObject().put("id","states").put("status",status)))}
  end("failed");assertTrue("Failed turn mislabeled",device.wait(Until.hasObject(By.text("Failed")),3000))
  end("interrupted");assertTrue("Interrupted turn mislabeled",device.wait(Until.hasObject(By.text("Stopped")),3000))
  end("completed");assertTrue("Completed turn implies input required",device.wait(Until.hasObject(By.text("Ready")),3000))
  main{Pocket.detail=JSONObject(requireNotNull(Pocket.detail).toString()).put("pending",org.json.JSONArray().put(JSONObject().put("id","question")))}
  assertTrue("Pending question not distinguished",device.wait(Until.hasObject(By.text("Needs you")),3000))

 }
}
