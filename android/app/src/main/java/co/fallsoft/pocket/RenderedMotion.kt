package co.fallsoft.pocket

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Integer timing avoids accumulated delay drift and skips overdue frames after a stall. */
internal fun renderedFrame(elapsedNanos: Long, fps: Int, count: Int): Int =
    (((elapsedNanos.coerceAtLeast(0) / 1_000_000_000L) % count * fps +
        elapsedNanos.coerceAtLeast(0) % 1_000_000_000L * fps / 1_000_000_000L) % count).toInt()

internal data class RenderedAtlas(val stem: String, val count: Int, val cell: Int,
    val columns: Int, val pageFrames: Int, val fps: Int = 60) {
    val pages get() = (count + pageFrames - 1) / pageFrames
}

/** Only resource decoding runs off the UI thread; pages are immutable after publication. */
private object RenderedPageCache {
    private val decodeLock = Mutex()
    private val pages = LinkedHashMap<Int, ImageBitmap>(16, .75f, true)
    private var bytes = 0L
    private const val budget = 32L * 1024 * 1024
    suspend fun load(context: Context, id: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (id == 0) return@withContext null
        decodeLock.withLock {
            synchronized(pages) { pages[id] } ?: decode(context, id)?.also { image ->
                    synchronized(pages) {
                        pages[id] = image; bytes += image.width.toLong() * image.height * 4
                        while (bytes > budget && pages.size > 1) {
                            val first = pages.entries.iterator(); val evicted = first.next().value
                            bytes -= evicted.width.toLong() * evicted.height * 4; first.remove()
                        }
                    }
                }
        }
    }
    private fun decode(context: Context, id: Int): ImageBitmap? = try {
        BitmapFactory.decodeResource(context.resources, id)
            ?.also { it.prepareToDraw() }?.asImageBitmap()
    } catch (_: android.content.res.Resources.NotFoundException) {
        null
    } catch (_: OutOfMemoryError) {
        // Drop cache references; do not recycle bitmaps still used by a visible Canvas.
        synchronized(pages) { pages.clear(); bytes = 0L }
        null
    }

}

internal class RenderedPlayback {
    var active = false
    // Read by the Canvas draw phase, so clock ticks do not recompose or relayout the icon.
    val frame = mutableIntStateOf(0)
    var images by mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())
}

@Composable internal fun rememberRenderedPlayback(context: Context, atlas: RenderedAtlas,
    animated: Boolean): RenderedPlayback {
    val playback = remember(atlas) { RenderedPlayback() }
    LaunchedEffect(atlas, animated) {
        playback.active = animated
        playback.frame.intValue = 0
        playback.images = emptyMap()
        if (!animated) return@LaunchedEffect
        fun resource(page: Int) = context.resources.getIdentifier(
            "${atlas.stem}_p$page", "drawable", context.packageName)
        val first = RenderedPageCache.load(context, resource(0)) ?: return@LaunchedEffect
        val next = RenderedPageCache.load(context, resource(1 % atlas.pages)) ?: return@LaunchedEffect
        playback.images = mapOf(0 to first, (1 % atlas.pages) to next)
        coroutineScope {
            val wantedPages = Channel<Int>(Channel.CONFLATED)
            launch {
                for (page in wantedPages) {
                    if (!playback.active) break
                    val current = playback.images[page] ?: RenderedPageCache.load(context, resource(page))
                    val upcoming = (page + 1) % atlas.pages
                    val prefetched = RenderedPageCache.load(context, resource(upcoming))
                    if (current == null || prefetched == null) {
                        playback.active = false
                        playback.images = emptyMap()
                    } else playback.images = mapOf(page to current, upcoming to prefetched)
                }
            }
            var currentPage = 0
            val start = withFrameNanos { it }
            try {
                while (playback.active) {
                    val frame = withFrameNanos { renderedFrame(it - start, atlas.fps, atlas.count) }
                    playback.frame.intValue = frame
                    val page = frame / atlas.pageFrames
                    if (page != currentPage) {
                        currentPage = page
                        wantedPages.trySend(page)
                    }
                }
            } finally { wantedPages.close() }
        }
    }
    return playback
}
