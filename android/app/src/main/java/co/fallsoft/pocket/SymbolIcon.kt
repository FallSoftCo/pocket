package co.fallsoft.pocket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

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
    val stem = (if (spinning) "symbol_spin_" else "symbol_motion_") + name.lowercase(java.util.Locale.ROOT)
    val hasSpin = spinning && name in setOf("Codex", "Psychology")
    val atlas = remember(name, hasSpin) {
        RenderedAtlas(if (hasSpin) stem else "symbol_motion_" + name.lowercase(java.util.Locale.ROOT),
            if (hasSpin) 160 else 120, if (hasSpin) 96 else 128,
            if (hasSpin) 8 else 5, if (hasSpin) 32 else 30)
    }
    val playback = rememberRenderedPlayback(context, atlas, animated && (!spinning || hasSpin))
    val fallback = ImageBitmap.imageResource(resource)
    Canvas(modifier.size(32.dp).pressMotion().semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
    }) {
        val frame = playback.frame.intValue
        val sheet = if (animated) playback.images[frame / atlas.pageFrames] else null
        if (sheet != null) {
            val localFrame = frame % atlas.pageFrames
            drawImage(sheet, srcOffset = IntOffset(localFrame % atlas.columns * atlas.cell,
                localFrame / atlas.columns * atlas.cell), srcSize = IntSize(atlas.cell, atlas.cell),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = tint.alpha.coerceAtLeast(.65f))
        } else drawImage(fallback, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = tint.alpha.coerceAtLeast(.65f))
    }
}
