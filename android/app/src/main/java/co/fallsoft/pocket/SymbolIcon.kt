package co.fallsoft.pocket

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private object SymbolMotionCache {
    private val decodeLock=Mutex()
    private val frames=object:LinkedHashMap<Int,ImageBitmap>(8,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,ImageBitmap>?)=size>8
    }
    suspend fun load(context:android.content.Context,id:Int):ImageBitmap=withContext(Dispatchers.IO){
        decodeLock.withLock{
            synchronized(frames){frames[id]}?:android.graphics.BitmapFactory.decodeResource(context.resources,id).asImageBitmap().also{
                synchronized(frames){frames[id]=it}
            }
        }
    }
}

// Authored Houdini symbols: preserve baked lighting rather than flattening with tint.
private val symbolResources = mapOf(
    "Codex" to R.drawable.symbol_codex,
    "User" to R.drawable.symbol_user,
    "ArrowBack" to R.drawable.symbol_arrowback,
    "ArrowDownward" to R.drawable.symbol_arrowdownward,
    "ArrowForward" to R.drawable.symbol_arrowforward,
    "ArrowUpward" to R.drawable.symbol_arrowupward,
    "AttachFile" to R.drawable.symbol_attachfile,
    "Build" to R.drawable.symbol_build,
    "Check" to R.drawable.symbol_check,
    "Computer" to R.drawable.symbol_computer,
    "Edit" to R.drawable.symbol_edit,
    "EditNote" to R.drawable.symbol_editnote,
    "ErrorOutline" to R.drawable.symbol_erroroutline,
    "ExpandLess" to R.drawable.symbol_expandless,
    "ExpandMore" to R.drawable.symbol_expandmore,
    "FolderOpen" to R.drawable.symbol_folderopen,
    "Inbox" to R.drawable.symbol_inbox,
    "Keyboard" to R.drawable.symbol_keyboard,
    "Layers" to R.drawable.symbol_layers,
    "Mic" to R.drawable.symbol_mic,
    "MoreHoriz" to R.drawable.symbol_morehoriz,
    "MoreVert" to R.drawable.symbol_morevert,
    "NotificationsActive" to R.drawable.symbol_notificationsactive,
    "NotificationsNone" to R.drawable.symbol_notificationsnone,
    "OpenInNew" to R.drawable.symbol_openinnew,
    "Pause" to R.drawable.symbol_pause,
    "PhoneAndroid" to R.drawable.symbol_phoneandroid,
    "PlayArrow" to R.drawable.symbol_playarrow,
    "Psychology" to R.drawable.symbol_psychology,
    "Search" to R.drawable.symbol_search,
    "Send" to R.drawable.symbol_send,
    "Stop" to R.drawable.symbol_stop,
    "Terminal" to R.drawable.symbol_terminal,
    "TravelExplore" to R.drawable.symbol_travelexplore,
    "Tune" to R.drawable.symbol_tune,
    "Unarchive" to R.drawable.symbol_unarchive,
    "VolumeUp" to R.drawable.symbol_volumeup
)
@Composable fun SymbolIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current,spinning:Boolean=false) {
    SymbolIcon(imageVector.name.substringAfterLast('.'), contentDescription, modifier, tint,spinning)
}

@Composable fun SymbolIcon(name: String, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current,spinning:Boolean=false) {
    val resource = requireNotNull(symbolResources[name]) { "Missing authored symbol: $name" }
    val context=LocalContext.current
    val animated=motionAllowed()
    val stripId=remember(name,spinning){context.resources.getIdentifier((if(spinning)"symbol_spin_" else "symbol_motion_")+name.lowercase(java.util.Locale.ROOT),"drawable",context.packageName)}
    val frameCount=if(spinning)16 else 12
    var strip by remember(stripId){mutableStateOf<ImageBitmap?>(null)}
    var frame by remember{mutableIntStateOf(0)}
    LaunchedEffect(animated,stripId){
        frame=0
        if(animated&&stripId!=0){
            strip=SymbolMotionCache.load(context,stripId)
            while(true){delay(166);frame=(frame+1)%frameCount}
        }
    }
    val sheet=strip
    if(animated&&sheet!=null){
        Canvas(modifier.size(32.dp).pressMotion().semantics{if(contentDescription!=null)this.contentDescription=contentDescription}){
            drawImage(sheet,srcOffset=IntOffset(frame*160,0),srcSize=IntSize(160,160),dstSize=IntSize(size.width.toInt(),size.height.toInt()),alpha=tint.alpha.coerceAtLeast(.65f))
        }
    }else Image(painterResource(resource), contentDescription, modifier.size(32.dp).pressMotion(), alpha = tint.alpha.coerceAtLeast(.65f))
}
