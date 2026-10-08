package co.fallsoft.pocket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** An isolated bundled WebRTC audio transport; account credentials never enter JavaScript. */
@SuppressLint("SetJavaScriptEnabled")
class NativeVoiceAudio(context:Context,private val explicitCompletion:Boolean=false,private val livePlayback:Boolean=false,private val callback:(String,String)->Unit){
    private val main=Handler(Looper.getMainLooper())
    private val view=WebView(context)
    private var sent:CompletableDeferred<Unit>?=null
    private var closed=false
    init{
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        view.settings.javaScriptEnabled=true
        view.settings.mediaPlaybackRequiresUserGesture=false
        view.settings.allowFileAccess=false
        view.settings.allowContentAccess=false
        view.webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true}
        view.addJavascriptInterface(object{
            @JavascriptInterface fun event(kind:String,data:String){main.post{if(closed)return@post;if(kind=="sent")sent?.complete(Unit);if(kind=="error")sent?.completeExceptionally(IllegalStateException(data));callback(kind,data)}}
        },"PocketAudio")
        view.loadDataWithBaseURL("https://pocket-voice.invalid/",context.assets.open("native-voice.html").bufferedReader().use{it.readText()}.replace("const explicitCompletion=false;","const explicitCompletion=$explicitCompletion;").replace("const livePlayback=false;","const livePlayback=$livePlayback;"),"text/html","UTF-8",null)
    }
    private fun js(code:String){view.evaluateJavascript(code,null)}
    fun answer(sdp:String)=js("answer(${JSONObject.quote(sdp)}).catch(e=>report('error',e.message))")
    suspend fun send(wav:ByteArray){val done=CompletableDeferred<Unit>();sent=done;try{js("send(${JSONObject.quote(Base64.encodeToString(wav,Base64.NO_WRAP))}).catch(e=>report('error',e.message))");withTimeout(135000){done.await()}}finally{sent=null}}
    fun beginInput(token:Long){sent=CompletableDeferred();js("beginInput($token)")}
    fun appendInput(token:Long,pcm:ByteArray)=js("appendInput($token,${JSONObject.quote(Base64.encodeToString(pcm,Base64.NO_WRAP))})")
    suspend fun finishInput(token:Long){val done=sent?:throw IllegalStateException("Recorded input was replaced.");try{js("finishInput($token)");withTimeout(135000){done.await()}}finally{sent=null}}
    fun cancelInput(){sent?.cancel();sent=null;js("cancelInput()")}
    fun speak(token:Long,expectedText:String="")=js("speak($token,${JSONObject.quote(expectedText)})")

    fun pause()=js("pauseOutput()")
    fun resume()=js("resumeOutput()")
    fun silence()=js("silence()")
    fun close(){if(closed)return;closed=true;sent?.cancel();js("closeVoice()");view.removeJavascriptInterface("PocketAudio");view.destroy()}
}
