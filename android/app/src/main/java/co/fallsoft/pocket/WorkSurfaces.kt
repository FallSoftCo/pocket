package co.fallsoft.pocket

import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared catalog geometry; each backend owns identities, ordering and pagination. */
@Composable internal fun WorkCatalogList(modifier:Modifier,state:LazyListState=rememberLazyListState(),reverseLayout:Boolean=true,contentPadding:PaddingValues=PaddingValues(vertical=12.dp),content:LazyListScope.()->Unit){
    LazyColumn(modifier.fillMaxWidth().padding(horizontal=16.dp),state=state,reverseLayout=reverseLayout,
        verticalArrangement=Arrangement.spacedBy(8.dp,if(reverseLayout)Alignment.Bottom else Alignment.Top),contentPadding=contentPadding,content=content)
}

/** The Codex card surface, including quiet state, activity glow and update highlight. */
@Composable internal fun WorkCardSurface(active:Boolean,needsInput:Boolean=false,accent:Color=Mint,highlight:Float=0f,modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit){
    val quiet=!active&&!needsInput
    val shape=RoundedCornerShape(8.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(if(quiet)Color(0xff080b10)else Panel).drawBehind{
        val glow=if(active||needsInput)1f else 0f
        drawRoundRect(Brush.radialGradient(listOf(accent.copy(alpha=.26f*glow),accent.copy(alpha=.06f*glow),Color.Transparent),Offset(size.width*.85f,0f),size.width*.95f),cornerRadius=CornerRadius(8.dp.toPx()))
    }.border(1.dp,if(quiet)Color(0xff252b34)else Line.copy(alpha=.7f),shape).border(2.dp,Mint.copy(alpha=highlight),shape),content=content)
}

@Composable internal fun ConversationMessageSurface(you:Boolean,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().then(if(you)Modifier.clip(RoundedCornerShape(6.dp)).background(Panel).padding(14.dp)else Modifier.padding(vertical=5.dp)),verticalArrangement=Arrangement.spacedBy(7.dp),content=content)
}

@Composable internal fun ConversationComposerRow(content:@Composable RowScope.()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).padding(start=18.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,content=content)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ConversationSendButton(hasDraft:Boolean,enabled:Boolean,busy:Boolean,label:String,modifier:Modifier=Modifier,onQueue:(()->Unit)?=null,onSend:()->Unit){
    Box(modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Ink).border(1.dp,if(hasDraft&&enabled)Mint else Muted.copy(alpha=.4f),RoundedCornerShape(12.dp))
        .combinedClickable(enabled=enabled,onClickLabel=label,onLongClickLabel=if(onQueue!=null)"Queue for next turn" else null,
            onLongClick=onQueue,onClick=onSend)
        .semantics{role=Role.Button;contentDescription=label;if(hasDraft&&onQueue!=null)customActions=listOf(CustomAccessibilityAction("Queue for next turn"){if(enabled){onQueue();true}else false})},contentAlignment=Alignment.Center){
        if(busy)CircularProgressIndicator(Modifier.size(18.dp),color=Mint,strokeWidth=2.dp)
        else SymbolIcon(Icons.Rounded.ArrowUpward,null,tint=Paper,modifier=Modifier.size(44.dp))
    }
}

/** The session input field; persistence and Enter/Shift+Enter routing remain caller-owned. */
@Composable internal fun ConversationDraftField(value:androidx.compose.ui.text.input.TextFieldValue,onValueChange:(androidx.compose.ui.text.input.TextFieldValue)->Unit,
    modifier:Modifier,enabled:Boolean=true,onSend:()->Unit,placeholder:@Composable ()->Unit){
    androidx.compose.foundation.text.BasicTextField(value,onValueChange,enabled=enabled,modifier=modifier.heightIn(min=46.dp).padding(top=12.dp,bottom=12.dp,end=8.dp),maxLines=3,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={onSend()}),
        textStyle=LocalTextStyle.current.copy(color=Paper,fontSize=15.sp,lineHeight=22.sp),
        cursorBrush=androidx.compose.ui.graphics.SolidColor(Mint),
        decorationBox={inner->Box(contentAlignment=Alignment.CenterStart){if(value.text.isEmpty())placeholder();inner()}})
}

/** The existing session dock/menu, with caller-owned backend actions. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ConversationActionsMenu(expanded:Boolean,onExpandedChange:(Boolean)->Unit,
    modifier:Modifier=Modifier,statusIcon:String?=null,statusLabel:String?=null,statusSpinning:Boolean=false,bottomActions:Boolean=false,
    content:@Composable ColumnScope.()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    // A fresh opening starts with current work before the popup is measured.
    val menuScroll=androidx.compose.runtime.remember(expanded,bottomActions){androidx.compose.foundation.ScrollState(if(bottomActions)Int.MAX_VALUE else 0)}
    Box{
        UsageDock(modifier.width(compactUsageDockWidth()),conversationOnly=true,compact=true,statusIcon=statusIcon,statusLabel=statusLabel,statusSpinning=statusSpinning,onOpen={onExpandedChange(true)})
        DropdownMenu(expanded,{onExpandedChange(false)},scrollState=menuScroll,modifier=Modifier.width(300.dp).heightIn(max=if(bottomActions)280.dp else 420.dp).semantics{testTagsAsResourceId=true},
            properties=androidx.compose.ui.window.PopupProperties(focusable=false),content={
                val report:@Composable ()->Unit={
                    TextButton({onExpandedChange(false);context.nextCompActivity()?.let{ResponsivenessReport.toggle(it)}},
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("native-measure-responsiveness")){
                        BilingualLabel(if(ResponsivenessReport.measuring)"Finish responsiveness report" else "Report a slowdown")
                    }
                }
                if(bottomActions){report();HorizontalDivider()}
                content()
                if(!bottomActions){HorizontalDivider();report()}
            })
    }
}
