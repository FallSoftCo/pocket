package co.fallsoft.pocket

import java.net.URI
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.decode.ImageDecoderDecoder
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.Buffer
import okio.buffer
import java.io.IOException

internal object LinkedImagePolicy {
    private fun uri(value:String)=runCatching{URI(value)}.getOrNull()
    fun isOpaqueFileLink(value:String):Boolean=uri(value)?.path?.startsWith("/api/files/")==true
    fun isImageUrl(value:String):Boolean {
        val path=uri(value)?.path?.lowercase()?:return false
        return path.startsWith("/api/files/")||path.matches(Regex(".*\\.(png|jpe?g|gif|webp|bmp|avif|svg)"))
    }
    fun resolve(value:String,base:String):String? {
        val candidate=uri(value)?:return null
        if(candidate.userInfo!=null)return null
        if(candidate.scheme==null){
            if(!value.startsWith("/api/files/")||candidate.rawAuthority!=null||candidate.path.contains(".."))return null
            val origin=uri(base)?:return null
            if(origin.scheme !in listOf("http","https")||origin.host==null)return null
            return origin.resolve(candidate).toString()
        }
        return value.takeIf{candidate.scheme.lowercase() in listOf("http","https")&&candidate.host!=null}
    }
    fun authenticated(url:String,base:String):Boolean {
        val target=uri(url)?:return false;val origin=uri(base)?:return false
        return target.scheme==origin.scheme&&target.host==origin.host&&target.port==origin.port&&target.path.startsWith("/api/files/")
    }
}

private object LinkedImageLoading {
    private val metadataClient=Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(20,TimeUnit.SECONDS).build()
    suspend fun imageMime(url:String,token:String?):Boolean=withContext(Dispatchers.IO){
        runCatching {
            fun request(head:Boolean)=Request.Builder().url(url).apply{
                if(head)head() else header("Range","bytes=0-0")
                if(token!=null)header("Authorization","Bearer $token")
            }.build()
            var needsGet=false
            metadataClient.newCall(request(true)).execute().use { response ->
                needsGet=response.code==405||response.code==501
                if(!needsGet)return@runCatching response.isSuccessful&&response.header("Content-Type").orEmpty().substringBefore(';').startsWith("image/",true)
            }
            metadataClient.newCall(request(false)).execute().use { response ->
                response.isSuccessful&&response.header("Content-Type").orEmpty().substringBefore(';').startsWith("image/",true)
            }
        }.getOrDefault(false)
    }

    private val instances=mutableMapOf<Boolean,ImageLoader>()
    // Public images follow normal redirects without auth; paired files never redirect.
    fun get(context:android.content.Context,authenticated:Boolean):ImageLoader=synchronized(this){
        instances[authenticated]?:ImageLoader.Builder(context.applicationContext).components {
            add(SvgDecoder.Factory())
            add(ImageDecoderDecoder.Factory())
        }.okHttpClient(Pocket.http.newBuilder().followRedirects(!authenticated).followSslRedirects(!authenticated).callTimeout(20,TimeUnit.SECONDS).addInterceptor { chain ->
        val response=chain.proceed(chain.request());val body=response.body
        if(body==null)response else {
            val limit=12L*1024*1024
            if(body.contentLength()>limit){response.close();throw IOException("Image exceeds preview limit")}
            val bounded=object:ForwardingSource(body.source()){
                var received=0L
                override fun read(sink:Buffer,byteCount:Long):Long {
                    val count=super.read(sink,byteCount.coerceAtMost(limit-received+1))
                    if(count>0)received+=count
                    if(received>limit)throw IOException("Image exceeds preview limit")
                    return count
                }
            }.buffer()
            response.newBuilder().body(object:ResponseBody(){
                override fun contentType()=body.contentType()
                override fun contentLength()=body.contentLength()
                override fun source()=bounded
            }).build()
        }
    }.build()).build().also{instances[authenticated]=it}
    }
}

@Composable internal fun linkedImageAllowed(image:MarkdownImage):Boolean {
    val url=LinkedImagePolicy.resolve(image.destination,Pocket.base)?:return false
    var accepted by remember(url,image.requiresMimeCheck,Pocket.token){mutableStateOf(!image.requiresMimeCheck)}
    LaunchedEffect(url,image.requiresMimeCheck,Pocket.token){
        if(image.requiresMimeCheck)accepted=LinkedImageLoading.imageMime(url,Pocket.token.takeIf{LinkedImagePolicy.authenticated(url,Pocket.base)})
    }
    return accepted
}

/** Fixed space while loading prevents asynchronous previews from jumping the chat. */
@Composable internal fun LinkedImagePreview(image:MarkdownImage){
    val context=LocalContext.current
    val url=remember(image.destination,Pocket.base){LinkedImagePolicy.resolve(image.destination,Pocket.base)}?:return
    val authenticated=LinkedImagePolicy.authenticated(url,Pocket.base)
    val loader=remember(context,authenticated){LinkedImageLoading.get(context,authenticated)}
    var attempt by remember(url){mutableIntStateOf(0)}
    var expanded by remember(url){mutableStateOf(false)}
    val request=remember(url,authenticated,Pocket.token,attempt){ImageRequest.Builder(context).data(url).size(1200,1200).apply{if(authenticated){
        addHeader("Authorization","Bearer ${Pocket.token}")
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(Pocket.token.toByteArray()).joinToString(""){"%02x".format(it)}
        memoryCacheKey("$url:$digest");diskCacheKey("$url:$digest")
    }}.build()}
    Surface(color=Ink,modifier=Modifier.fillMaxWidth().height(240.dp).clickable{expanded=true}){
        SubcomposeAsyncImage(model=request,imageLoader=loader,contentDescription=image.description.ifBlank{"Image"},contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize(),loading={Box(Modifier.padding(16.dp)){Text("Loading image…",color=Muted,fontSize=14.sp)}},error={Column(Modifier.padding(16.dp)){Text("Image unavailable",color=Muted,fontSize=14.sp);TextButton(onClick={attempt++}){BilingualLabel("Retry",color=Mint)}}})
    }
    if(expanded)LinkedImageViewer(image,request,loader){expanded=false}
}

@Composable private fun LinkedImageViewer(image:MarkdownImage,request:ImageRequest,loader:ImageLoader,onClose:()->Unit){
    var scale by remember(image.destination){mutableFloatStateOf(1f)}
    var offset by remember(image.destination){mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState { zoom,pan,_ ->
        scale=(scale*zoom).coerceIn(1f,6f)
        offset=if(scale<=1f)Offset.Zero else offset+pan
    }
    val fullRequest=remember(request){request.newBuilder().size(2400,2400).build()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)){
        Surface(color=Ink,modifier=Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize()){
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().transformable(transform)){
                    SubcomposeAsyncImage(model=fullRequest,imageLoader=loader,contentDescription=image.description.ifBlank{"Image"},contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y},loading={Text("Loading image…",color=Muted,modifier=Modifier.padding(20.dp))},error={Text("Image unavailable",color=Muted,modifier=Modifier.padding(20.dp))})
                }
                TextButton(onClick=onClose,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).navigationBarsPadding()){BilingualLabel("Close image",color=Mint)}
            }
        }
    }
}
