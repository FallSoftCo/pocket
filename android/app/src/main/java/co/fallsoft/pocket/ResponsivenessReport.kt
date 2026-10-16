package co.fallsoft.pocket

import android.content.ContentValues
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.FrameMetrics
import android.view.Window
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, bounded on-device measurement. No text, credentials, screen/audio or network upload. */
internal object ResponsivenessReport {
    var measuring by mutableStateOf(false);private set
    var savedName by mutableStateOf("");private set
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var session:Session?=null
    fun toggle(activity:MainActivity){session?.let{finish(it);return};start(activity)}
    fun start(activity:MainActivity){
        check(Looper.myLooper()==Looper.getMainLooper())
        if(session!=null)return
        val current=Session(activity);session=current;measuring=true;savedName=""
        try{current.start();notice("Measuring responsiveness for 15 seconds")}
        catch(e:Exception){finish(current);notice("Could not start responsiveness measurement")}
    }
    private fun notice(text:String){main.post{Toast.makeText(Pocket.context,PocketImmersion.label(text),Toast.LENGTH_LONG).show()}}
    private fun finish(current:Session){
        if(!current.closed.compareAndSet(false,true))return
        main.post{
            current.activity.get()?.let{activity->runCatching{activity.window.removeOnFrameMetricsAvailableListener(current.listener)};activity.lifecycle.removeObserver(current.lifecycle)}
            if(session===current){session=null;measuring=false}
            current.metricsThread.quitSafely()
            Pocket.scope.launch(Dispatchers.IO){
                try{
                    val report=current.report();val body=report.toString(2)
                    val name="nextcomp-responsiveness-${BuildConfig.VERSION_CODE}-${current.startedAt}.json"
                    val private=File(Pocket.context.filesDir,"diagnostics").apply{mkdirs()}
                    File(private,"responsiveness-latest.json").writeText(body)
                    if(Build.VERSION.SDK_INT>=29){
                        val resolver=Pocket.context.contentResolver
                        val values=ContentValues().apply{
                            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH,"Download/NextComp");put(MediaStore.Downloads.IS_PENDING,1)
                        }
                        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                        try{
                            requireNotNull(resolver.openOutputStream(uri)).bufferedWriter().use{it.write(body)}
                            resolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
                        }catch(e:Exception){resolver.delete(uri,null,null);throw e}
                    }else{
                        val external=File(requireNotNull(Pocket.context.getExternalFilesDir(null)),"diagnostics").apply{mkdirs()}
                        File(external,name).writeText(body)
                    }
                    main.post{savedName=name};notice(if(Build.VERSION.SDK_INT>=29)"Responsiveness report saved in Downloads" else "Responsiveness report saved in app files")
                }catch(e:Exception){notice("Could not export responsiveness report")}
            }
        }
    }
    private class Session(activity:MainActivity){
        val activity=WeakReference(activity)
        val startedAt=System.currentTimeMillis()
        private val started=SystemClock.elapsedRealtime()
        val closed=AtomicBoolean(false)
        val metricsThread=HandlerThread("NextComp bounded frame measurement")
        private val frames=ArrayList<LongArray>();private val acknowledgements=ArrayList<Long>()
        private val stalls=ArrayList<JSONObject>();private var totalFrames=0L;private var dropped=0L
        private val metadata=JSONObject().put("versionCode",BuildConfig.VERSION_CODE).put("sdk",Build.VERSION.SDK_INT)
            .put("deviceModel",Build.MODEL).put("density",activity.resources.displayMetrics.density)
            .put("fontScale",activity.resources.configuration.fontScale).put("windowWidthPx",activity.window.decorView.width)
            .put("windowHeightPx",activity.window.decorView.height).put("nativeRooms",BackendNavigation.nativeSelected())
        val listener=Window.OnFrameMetricsAvailableListener{_,metrics,missed->
            synchronized(frames){if(!closed.get()){
                totalFrames++;dropped+=missed
                if(frames.size<2048)frames.add(longArrayOf(SystemClock.elapsedRealtime()-started,
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(FrameMetrics.SYNC_DURATION),metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                    metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)))
            }}
        }
        val lifecycle=object:DefaultLifecycleObserver{override fun onStop(owner:LifecycleOwner){finish(this@Session)}}
        fun start(){
            metricsThread.start()
            requireNotNull(activity.get()).let{it.window.addOnFrameMetricsAvailableListener(listener,Handler(metricsThread.looper));it.lifecycle.addObserver(lifecycle)}
            Thread({
                while(!closed.get()&&SystemClock.elapsedRealtime()-started<15_000){
                    val ack=CountDownLatch(1);val begin=SystemClock.elapsedRealtimeNanos()
                    main.post{val duration=SystemClock.elapsedRealtimeNanos()-begin;synchronized(acknowledgements){if(acknowledgements.size<512)acknowledgements.add(duration)};ack.countDown()}
                    if(!ack.await(80,TimeUnit.MILLISECONDS)&&!closed.get()){
                        synchronized(stalls){if(stalls.size<16)stalls.add(JSONObject().put("atMs",SystemClock.elapsedRealtime()-started)
                            .put("stack",JSONArray(Looper.getMainLooper().thread.stackTrace.take(24).map{it.toString()})))}
                        ack.await(500,TimeUnit.MILLISECONDS)
                    }
                    Thread.sleep(50)
                }
                finish(this)
            },"NextComp bounded input measurement").apply{isDaemon=true;start()}
        }
        fun report():JSONObject{
            val rows=synchronized(frames){frames.map{values->JSONArray(values.toList())}}
            val acks=synchronized(acknowledgements){acknowledgements.toList()}
            val blocked=synchronized(stalls){stalls.toList()}
            return JSONObject().put("schema",1).put("capturedAt",startedAt).put("durationMs",SystemClock.elapsedRealtime()-started)
                .put("metadata",metadata).put("totalFrames",totalFrames).put("droppedFrameCallbacks",dropped)
                .put("frameColumns",JSONArray(listOf("atMs","totalNs","inputNs","layoutNs","drawNs","syncNs","commandNs","swapNs")))
                .put("frames",JSONArray(rows)).put("mainAcknowledgementNs",JSONArray(acks)).put("mainStalls",JSONArray(blocked))
                .put("limits","User-initiated 15-second foreground capture; bounded samples. No conversation text, account IDs, tokens, audio, screenshots or automatic upload.")
        }
    }
}
