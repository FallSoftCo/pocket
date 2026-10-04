package co.fallsoft.pocket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object PocketAutomation {
    var connected by mutableStateOf(false); internal set
    var revision by mutableIntStateOf(0); private set
    var allowed:Boolean
        get(){revision;return Pocket.prefs.getBoolean("automationAllowed",false)}
        set(value){Pocket.prefs.edit().putBoolean("automationAllowed",value).apply();revision++}
    fun systemEnabled(c:Context):Boolean {
        val enabled=Settings.Secure.getString(c.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false
        return enabled.split(':').any{it.equals("${c.packageName}/${PocketAutomationService::class.java.name}",true)||it.endsWith("/.PocketAutomationService")}
    }
}

/** Loopback-only, authenticated phone control. Android must bind this service explicitly. */
class PocketAutomationService:AccessibilityService(){
    private val main=Handler(Looper.getMainLooper())
    private val pool=Executors.newCachedThreadPool()
    @Volatile private var socket:ServerSocket?=null

    override fun onServiceConnected(){
        super.onServiceConnected();serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS};android.util.Log.i("PocketVoice","Key filter connected: ${serviceInfo.flags}");PocketAutomation.connected=true;PocketVoice.keysEnabled=true
        if(socket!=null)return
        pool.execute{
            try{
                val server=ServerSocket(18881,8,InetAddress.getByName("127.0.0.1"));socket=server
                while(!server.isClosed)try{val client=server.accept();pool.execute{handle(client)}}catch(e:Exception){if(!server.isClosed)throw e}
            }catch(_:Exception){PocketAutomation.connected=false}
        }
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){}
    override fun onKeyEvent(event:android.view.KeyEvent):Boolean{if(event.keyCode in listOf(24,25))android.util.Log.d("PocketVoice","Volume key ${event.keyCode} action ${event.action}");return PocketVoice.key(event)||super.onKeyEvent(event)}
    override fun onInterrupt(){}
    override fun onDestroy(){socket?.close();socket=null;pool.shutdownNow();PocketAutomation.connected=false;PocketVoice.keysEnabled=false;super.onDestroy()}

    private fun handle(client:Socket){client.use{c->
        c.soTimeout=10000
        try{
            val reader=BufferedReader(InputStreamReader(c.getInputStream(),Charsets.UTF_8));val request=reader.readLine()?:return
            val parts=request.split(' ');if(parts.size<2){respond(c,400,"text/plain","Bad request".toByteArray());return}
            val headers=mutableMapOf<String,String>();while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;val at=line.indexOf(':');if(at>0)headers[line.substring(0,at).lowercase()]=line.substring(at+1).trim()}
            val expected=Pocket.prefs.getString(Pocket.key("automationSecret",true),null)
            if(expected.isNullOrBlank()||headers["authorization"]!="Bearer $expected"){respond(c,401,"text/plain","Unauthorized".toByteArray());return}
            if(!PocketAutomation.allowed){respond(c,423,"text/plain","Phone control is paused in NextComp settings.".toByteArray());return}
            val length=(headers["content-length"]?.toIntOrNull()?:0).coerceIn(0,65536);val chars=CharArray(length);var read=0
            while(read<length){val count=reader.read(chars,read,length-read);if(count<0)break;read+=count}
            when(parts[1]){
                "/snapshot"->respondJson(c,onMain{snapshot()})
                "/screenshot"->respond(c,200,"image/png",screenshot())
                "/action"->respondJson(c,onMain{action(JSONObject(String(chars,0,read)))})
                else->respond(c,404,"text/plain","Not found".toByteArray())
            }
        }catch(e:Exception){try{respondJson(c,JSONObject().put("ok",false).put("error",e.message?:"Phone control failed"),500)}catch(_:Exception){}}
    }}
    private fun respondJson(socket:Socket,value:JSONObject,status:Int=200)=respond(socket,status,"application/json",value.toString().toByteArray())
    private fun respond(socket:Socket,status:Int,type:String,body:ByteArray){
        val label=when(status){200->"OK";400->"Bad Request";401->"Unauthorized";404->"Not Found";423->"Locked";else->"Error"}
        socket.getOutputStream().apply{write("HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
    }
    private fun <T> onMain(block:()->T):T {
        if(Looper.myLooper()==Looper.getMainLooper())return block()
        val task=FutureTask(Callable{block()});main.post(task);return task.get(8,TimeUnit.SECONDS)
    }
    private fun snapshot():JSONObject {
        val root=rootInActiveWindow?:return JSONObject().put("ok",false).put("error","No active Android window")
        val nodes=JSONArray();var count=0
        fun walk(node:AccessibilityNodeInfo,path:String,depth:Int){
            if(count++>=500||depth>20)return
            val bounds=Rect();node.getBoundsInScreen(bounds)
            val item=JSONObject().put("path",path).put("class",node.className?.toString()?.substringAfterLast('.')?:"")
                .put("text",if(node.isPassword)"" else node.text?.toString()?.take(500)?:"").put("description",node.contentDescription?.toString()?.take(500)?:"")
                .put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))).put("clickable",node.isClickable).put("scrollable",node.isScrollable).put("editable",node.isEditable).put("password",node.isPassword).put("enabled",node.isEnabled)
            if(item.getString("text").isNotBlank()||item.getString("description").isNotBlank()||node.isClickable||node.isScrollable||node.isEditable)nodes.put(item)
            for(index in 0 until node.childCount)node.getChild(index)?.let{walk(it,"$path.$index",depth+1)}
        }
        walk(root,"0",0)
        return JSONObject().put("ok",true).put("package",root.packageName?.toString()?:"").put("windowTitle",root.window?.title?.toString()?:"").put("nodes",nodes)
    }
    private fun node(path:String):AccessibilityNodeInfo? {
        var current=rootInActiveWindow?:return null
        val indices=path.split('.').mapNotNull{it.toIntOrNull()};if(indices.firstOrNull()!=0)return null
        for(index in indices.drop(1))current=current.getChild(index)?:return null
        return current
    }
    private fun action(request:JSONObject):JSONObject {
        val ok=when(request.optString("type")){
            "tap"->gesture(request.getDouble("x").toFloat(),request.getDouble("y").toFloat(),request.getDouble("x").toFloat(),request.getDouble("y").toFloat(),80)
            "click"->{var target=node(request.getString("path"));while(target!=null&&!target.isClickable)target=target.parent;target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true}
            "scroll"->{val width=resources.displayMetrics.widthPixels.toFloat();val height=resources.displayMetrics.heightPixels.toFloat();when(request.getString("direction")){"up"->gesture(width*.5f,height*.35f,width*.5f,height*.75f,350);"down"->gesture(width*.5f,height*.75f,width*.5f,height*.35f,350);"left"->gesture(width*.3f,height*.5f,width*.8f,height*.5f,350);else->gesture(width*.8f,height*.5f,width*.3f,height*.5f,350)}}
            "text"->{val target=rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);if(target==null||!target.isEditable||target.isPassword)false else target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,request.getString("text"))})}
            "key"->performGlobalAction(when(request.getString("key")){"home"->GLOBAL_ACTION_HOME;"recents"->GLOBAL_ACTION_RECENTS;else->GLOBAL_ACTION_BACK})
            else->false
        }
        return JSONObject().put("ok",ok)
    }
    private fun gesture(fromX:Float,fromY:Float,toX:Float,toY:Float,duration:Long):Boolean {
        val path=Path().apply{moveTo(fromX,fromY);lineTo(toX,toY)}
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build(),null,null)
    }
    private fun screenshot():ByteArray {
        if(Build.VERSION.SDK_INT<30)throw Error("Screenshots require Android 11 or newer")
        return screenshotApi30()
    }
    @RequiresApi(30)
    private fun screenshotApi30():ByteArray {
        val latch=CountDownLatch(1);var bytes:ByteArray?=null;var failure="Screenshot failed"
        takeScreenshot(Display.DEFAULT_DISPLAY,pool,object:TakeScreenshotCallback{
            override fun onSuccess(result:ScreenshotResult){
                try{val bitmap=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace);val out=ByteArrayOutputStream();bitmap?.compress(Bitmap.CompressFormat.PNG,100,out);bytes=out.toByteArray();bitmap?.recycle();result.hardwareBuffer.close()}catch(e:Exception){failure=e.message?:failure}finally{latch.countDown()}
            }
            override fun onFailure(errorCode:Int){failure="Screenshot failed ($errorCode)";latch.countDown()}
        })
        if(!latch.await(10,TimeUnit.SECONDS))throw Error("Screenshot timed out")
        return bytes?.takeIf{it.isNotEmpty()}?:throw Error(failure)
    }
}
