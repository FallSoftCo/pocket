package co.fallsoft.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal fun usefulConversationResults(notes:List<JSONObject>,rows:List<JSONObject>):List<JSONObject>{
    fun normalized(value:String)=value.replace(Regex("\\s+")," ").trim().lowercase(java.util.Locale.ROOT)
    val users=rows.filter{it.s("kind")=="user"}.map{normalized(it.s("text"))}.toSet()
    val seen=mutableSetOf<String>()
    return notes.filter{note->val text=normalized(note.s("text"));val row=rows.firstOrNull{it.s("id")==note.s("id")||it.s("itemId")==note.s("id")}
        text.isNotBlank()&&text !in users&&row?.s("kind")!="user"&&seen.add(text)}
}
/** Same presentation model: desktop/tablet rail, narrow-window end drawer. */
@Composable internal fun ConversationReviewScaffold(open:Boolean,onClose:()->Unit,review:@Composable ()->Unit,content:@Composable ()->Unit){
    val width=LocalConfiguration.current.screenWidthDp
    if(width>=1000){Row(Modifier.fillMaxSize()){
        Box(Modifier.weight(1f).fillMaxHeight()){content()}
        if(open)Surface(color=Panel,modifier=Modifier.width(340.dp).fillMaxHeight()){review()}
    }}else{
        val direction=LocalLayoutDirection.current
        val state=rememberDrawerState(if(open)DrawerValue.Open else DrawerValue.Closed)
        val close by rememberUpdatedState(onClose)
        LaunchedEffect(open){if(open)state.open()else state.close()}
        LaunchedEffect(state){snapshotFlow{state.currentValue}.drop(1).collect{if(it==DrawerValue.Closed)close()}}
        // Only drawer placement is RTL. Content retains its actual reading direction.
        // Staying in this Activity preserves volume keys, IME and foreground read ownership.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
            ModalNavigationDrawer(drawerState=state,gesturesEnabled=open,drawerContent={
                CompositionLocalProvider(LocalLayoutDirection provides direction){
                    ModalDrawerSheet(modifier=Modifier.width(minOf(420.dp,(width*.96f).dp)),drawerContainerColor=Panel,windowInsets=WindowInsets(0,0,0,0)){review()}
                }
            }){CompositionLocalProvider(LocalLayoutDirection provides direction){content()}}
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ConversationReviewContent(requests:List<JSONObject>,results:List<JSONObject>,other:List<JSONObject>,onClose:()->Unit,onReply:(JSONObject)->Unit,onSource:(JSONObject)->Unit,onDismiss:(JSONObject)->Unit,removing:Set<String>){
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal=16.dp)){
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            BilingualLabel(if(requests.isNotEmpty()||other.isNotEmpty())"Needs you" else "Questions and results",fontSize=20.sp,centered=false,modifier=Modifier.weight(1f))
            TextButton(onClose,modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription="Close questions"}){BilingualLabel("Close")}
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)){
            if(requests.isEmpty()&&other.isEmpty())item{BilingualLabel("No unanswered questions",color=Muted,centered=false)}
            items(requests,key={"request:"+it.s("id")}){request->RequestCard(request)}
            items(other,key={"attention:"+it.s("id")}){notice->Surface(color=Ink,modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    BilingualLabel(notice.s("title","Another session needs you"),centered=false)
                    Text(notice.s("body"),fontSize=15.sp,color=Paper)
                    TextButton({Pocket.open(notice.s("thread_id"));onClose()}){BilingualLabel("Open and answer")}
                }
            }}
            if(results.isNotEmpty())item{BilingualLabel("Saved results",fontSize=16.sp,color=Mint,centered=false)}
            items(results,key={"result:"+it.s("id")}){note->Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
                BilingualMessage("review-result:"+note.s("id"),note.s("text"))
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    TextButton({onReply(note)}){BilingualLabel("Reply")}
                    TextButton({onSource(note)}){BilingualLabel("In conversation")}
                    TextButton({onDismiss(note)},enabled=note.s("id") !in removing){BilingualLabel(if(note.s("id") in removing)"Removing…" else "Dismiss",color=Muted)}
                }
                HorizontalDivider(color=Line)
            }}
        }
    }
}
