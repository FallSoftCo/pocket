package co.fallsoft.pocket

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.alpha

/** Page the actual reading viewport, in physical screen direction.
 * Preparation may hydrate one missing edge; it never replays a worker command.
 * The owner controls read intent and follow mode. Its scope cancels on navigation.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ReaderPagingButtons(
    list:LazyListState,hasEarlier:Boolean=false,
    onPrepare:suspend (up:Boolean)->Unit={},
    onSettled:(up:Boolean,completed:Boolean)->Unit={_,_->},
    onError:(Exception)->Unit={}
){
    val jobs=rememberCoroutineScope()
    val prepare by rememberUpdatedState(onPrepare)
    val settled by rememberUpdatedState(onSettled)
    val reportError by rememberUpdatedState(onError)
    var paging by remember(list){mutableStateOf<Boolean?>(null)}
    var pagingJob by remember(list){mutableStateOf<Job?>(null)}
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged){if(dragged)pagingJob?.cancel()}
    val reverse=list.layoutInfo.reverseLayout
    val upEnabled=(if(reverse)list.canScrollForward else list.canScrollBackward)||hasEarlier
    val downEnabled=if(reverse)list.canScrollBackward else list.canScrollForward
    fun page(up:Boolean){
        if(paging!=null)return
        paging=up
        pagingJob=jobs.launch{
            var completed=false
            try{
                prepare(up)
                withFrameNanos{}
                val height=list.layoutInfo.viewportSize.height.toFloat()
                val direction=if(up xor list.layoutInfo.reverseLayout)-1f else 1f
                if(height>0f){
                    // A page is one immediate viewport replacement, never an animated
                    // journey through text the reader did not choose to inspect.
                    list.scrollBy(height*direction)
                }
                completed=true
            }catch(e:CancellationException){throw e}
            catch(e:Exception){reportError(e)}
            finally{paging=null;settled(up,completed)}
        }
    }
    for(up in listOf(true,false)){
        val label=if(up)"Page up" else "Page down"
        IconButton({page(up)},enabled=paging==null&&(if(up)upEnabled else downEnabled),
            modifier=Modifier.size(48.dp).testTag(if(up)"reader-page-up" else "reader-page-down").semantics{
                testTagsAsResourceId=true
                contentDescription=PocketImmersion.label(label)
                if(paging==up)stateDescription="Paging"
            }){
            SymbolIcon(if(paging==up)"Codex" else if(up)"ArrowUpward" else "ArrowDownward",null,
                Modifier.size(32.dp).alpha(if(paging==up||(paging==null&&(if(up)upEnabled else downEnabled)))1f else .32f),spinning=paging==up)
        }
    }
}
