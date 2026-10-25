package co.fallsoft.pocket

import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray

internal data class GalleryPreviewCard(val title:String,val poster:String)
internal object GalleryCardLinks {
    fun card(value:String):URI?=runCatching{URI(value)}.getOrNull()?.takeIf{
        it.scheme in listOf("http","https")&&it.host!=null&&it.userInfo==null&&it.path=="/8bar/"&&!it.fragment.isNullOrBlank()
    }
    fun asset(base:URI,value:String):String?=runCatching{base.resolve(value)}.getOrNull()?.takeIf{
        value.isNotBlank()&&it.scheme==base.scheme&&it.host==base.host&&it.port==base.port&&it.userInfo==null&&it.normalize().path.startsWith("/8bar/")
    }?.toString()
}
/** Existing public-on-tailnet metadata only. No device token, worker or chat message. */
internal object GalleryPreviewCatalog {
    private data class Entry(val at:Long,val job:Deferred<Map<String,GalleryPreviewCard>>)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex()
    private val permits=Semaphore(2)
    private val cache=LinkedHashMap<String,Entry>()
    private val http=Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(12,TimeUnit.SECONDS).build()
    suspend fun resolve(destination:String):GalleryPreviewCard? {
        val uri=GalleryCardLinks.card(destination)?:return null
        val base=uri.resolve("/8bar/");val key=base.toString();val now=System.currentTimeMillis()
        val job=lock.withLock{
            cache[key]?.takeIf{now-it.at<60_000}?.job?:scope.async{permits.withPermit{
                val request=Request.Builder().url(base.resolve("showcase-catalog.json").toString()).get().build()
                http.newCall(request).execute().use{response->
                    check(response.isSuccessful){"Gallery metadata unavailable"}
                    val body=requireNotNull(response.body);val limit=16L*1024*1024
                    check(body.contentLength()<=limit){"Gallery metadata exceeds preview limit"}
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384);val input=body.byteStream();while(true){val count=input.read(buffer);if(count<0)break;check(output.size()+count<=limit);output.write(buffer,0,count)};val bytes=output.toByteArray()
                    val cards=JSONArray(String(bytes,Charsets.UTF_8));check(cards.length()<=20_000)
                    buildMap{cards.objects().forEach{card->
                        val poster=GalleryCardLinks.asset(base,card.s("poster"));if(poster!=null)put(card.s("id"),GalleryPreviewCard(card.s("title"),poster))
                    }}
                }
            }}.also{cache[key]=Entry(now,it);while(cache.size>4){val oldest=cache.keys.first();cache.remove(oldest)}}
        }
        return job.await()[uri.fragment]
    }
    suspend fun invalidate(destination:String){val uri=GalleryCardLinks.card(destination)?:return;lock.withLock{cache.remove(uri.resolve("/8bar/").toString())}}
}
@Composable internal fun GalleryCardPreview(image:MarkdownImage){
    var card by remember(image.destination){mutableStateOf<GalleryPreviewCard?>(null)}
    var unavailable by remember(image.destination){mutableStateOf(false)}
    var attempt by remember(image.destination){mutableIntStateOf(0)}
    val uri=LocalUriHandler.current
    LaunchedEffect(image.destination,attempt){
        unavailable=false
        try{if(attempt>0)GalleryPreviewCatalog.invalidate(image.destination);card=GalleryPreviewCatalog.resolve(image.destination);unavailable=card==null}
        catch(e:CancellationException){throw e}catch(_:Exception){unavailable=true}
    }
    Column(Modifier.fillMaxWidth()){
        val preview=card
        if(preview!=null){LinkedImagePreview(MarkdownImage(preview.poster,preview.title.ifBlank{image.description}));if(preview.title.isNotBlank())Text(preview.title,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))}
        else Surface(color=Panel,modifier=Modifier.fillMaxWidth().height(if(unavailable)72.dp else 240.dp)){
            Row(Modifier.padding(14.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                if(!unavailable){AnimatedMark(24);Spacer(Modifier.width(10.dp));BilingualLabel("Loading preview…",color=Muted)}
                else {BilingualLabel("Preview unavailable",color=Muted,modifier=Modifier.weight(1f));TextButton({attempt++}){BilingualLabel("Retry")}}
            }
        }
        TextButton({uri.openUri(image.destination)},modifier=Modifier.heightIn(min=44.dp)){BilingualLabel("Open full gallery",fontSize=13.sp)}
    }
}
