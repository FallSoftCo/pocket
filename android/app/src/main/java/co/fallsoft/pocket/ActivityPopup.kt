package co.fallsoft.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Opens at the tapped control, without a travelling sheet or entrance animation. */
@Composable fun ActivityPopup(expanded:Boolean,onDismiss:()->Unit,sourceThread:String?=null,passage:String?=null,controlsFirst:Boolean=false,details:@Composable ColumnScope.()->Unit={}){
    if(!expanded)return
    val density=androidx.compose.ui.platform.LocalDensity.current
    val gap=with(density){8.dp.roundToPx()}
    // Read host insets before entering the separate Popup window. Its window may
    // not receive the IME inset even while the activity does.
    val imeBottom=WindowInsets.ime.getBottom(density)
    val systemBottom=WindowInsets.systemBars.getBottom(density)
    val systemTop=WindowInsets.systemBars.getTop(density)
    val screenHeight=with(density){LocalConfiguration.current.screenHeightDp.dp.roundToPx()}
    val availableHeight=(screenHeight-maxOf(imeBottom,systemBottom)-systemTop-gap*2).coerceAtLeast(1)
    val popupHeight=with(density){minOf((screenHeight*.48f).toInt(),availableHeight).toDp()}
    val position=remember(gap,imeBottom,systemBottom,systemTop){object:PopupPositionProvider{
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset{
            return activityPopupOffset(anchorBounds,windowSize,popupContentSize,gap,imeBottom,systemTop,systemBottom)
        }
    }}
    var replyTarget by remember{mutableStateOf<String?>(null)}
    val drafts=remember{mutableStateMapOf<String,String>()}
    var text by remember(replyTarget){mutableStateOf(drafts[replyTarget].orEmpty())}
    val scroll=rememberScrollState()
    var controls by remember{mutableStateOf(false)}
    val profile=remember{Pocket.local to Pocket.token}
    LaunchedEffect(Pocket.local,Pocket.token){if(profile!=(Pocket.local to Pocket.token))onDismiss()}
    val attention=Pocket.attention.filter{!PocketAttention.dismissed(it.optLong("id"))}.map{it.s("thread_id")}.filter{it.isNotBlank()}
    val ids=remember { (listOfNotNull(sourceThread)+attention+Pocket.tasks.filter{it.status=="active"}.map{it.id}+Pocket.tasks.sortedByDescending{it.updated}.map{it.id}).distinct().take(8) }
    val inputFocus=remember{FocusRequester()}
    LaunchedEffect(replyTarget){if(replyTarget!=null){scroll.scrollTo(0);inputFocus.requestFocus()}}
    fun send(){val target=replyTarget;if(target!=null&&text.isNotBlank()&&!Pocket.sending){val submitted=text;Pocket.reply(submitted,threadId=target,mode="steer"){if(drafts[target]==submitted)drafts.remove(target);if(replyTarget==target&&text==submitted)text=""}}}
    Popup(popupPositionProvider=position,onDismissRequest=onDismiss,properties=PopupProperties(focusable=true)){
        Surface(color=Panel,shape=RoundedCornerShape(18.dp),shadowElevation=12.dp,modifier=Modifier.widthIn(max=(LocalConfiguration.current.screenWidthDp-24).dp).width(360.dp)){
            Column(Modifier.heightIn(max=popupHeight).padding(12.dp)){
            Column(Modifier.weight(1f,fill=false).verticalScroll(scroll)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Box(Modifier.padding(12.dp)){BilingualLabel("Activity")};TextButton(onDismiss){BilingualLabel("Close")}}
                if(controlsFirst){details();HorizontalDivider(color=Line,modifier=Modifier.padding(vertical=8.dp))}
                replyTarget?.let{id->ImmersionText("popup-title:"+id,Pocket.tasks.firstOrNull{it.id==id}?.title?:"Reply",rescue=false,color=Mint,fontSize=12.sp,modifier=Modifier.padding(8.dp));OutlinedTextField(text,{text=it;drafts[id]=it},modifier=Modifier.fillMaxWidth().focusRequester(inputFocus).onPreviewKeyEvent{event->if((event.key==Key.Enter||event.key==Key.NumPadEnter)&&!event.isShiftPressed){if(event.type==KeyEventType.KeyDown)send();true}else false},placeholder={BilingualLabel("Message Codex…",centered=false)},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),maxLines=4)}
                if(replyTarget!=null&&Pocket.error.isNotBlank())WorkflowText(Pocket.error,color=Coral,modifier=Modifier.padding(8.dp))
                if(passage!=null&&sourceThread!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    ImmersionText("popup-title:"+sourceThread,Pocket.tasks.firstOrNull{it.id==sourceThread}?.title?:"Conversation",rescue=false,modifier=Modifier.weight(1f).heightIn(min=56.dp).clickable{onDismiss();Pocket.open(sourceThread)}.padding(12.dp),maxLines=2,overflow=TextOverflow.Ellipsis,color=Paper)
                    TextButton({replyTarget=sourceThread}){BilingualLabel("Reply")}
                }
                passage?.let{Text(it,color=Paper,modifier=Modifier.padding(12.dp))}
                ids.filterNot{passage!=null&&it==sourceThread}.forEach{id->val task=Pocket.tasks.firstOrNull{it.id==id};val alert=Pocket.attention.firstOrNull{it.s("thread_id")==id}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        Column(Modifier.weight(1f).heightIn(min=56.dp).clickable{onDismiss();Pocket.open(id)}.padding(horizontal=12.dp,vertical=8.dp)){
                            ImmersionText("popup-title:"+id,task?.title?:alert?.s("title")?:"Conversation",rescue=false,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(id in attention)Coral else Paper)
                            ImmersionText("popup-preview:"+id,task?.preview?.takeIf{it.isNotBlank()}?:alert?.s("body").orEmpty(),rescue=false,maxLines=2,overflow=TextOverflow.Ellipsis,color=Muted,fontSize=12.sp)
                        }
                        TextButton({replyTarget=id}){BilingualLabel("Reply")}
                    }
                }

                if(!controlsFirst){
                    TextButton({controls=!controls},modifier=Modifier.fillMaxWidth()){BilingualLabel(if(controls)"Hide controls" else "Controls & usage")}
                    if(controls)details()
                }
            }
            if(replyTarget!=null)Button({send()},enabled=text.isNotBlank()&&!Pocket.sending,modifier=Modifier.fillMaxWidth()){BilingualLabel(if(Pocket.sending)"Sending…" else "Send",color=Ink)}
            }
        }
    }
}

internal fun activityPopupOffset(anchor:IntRect,window:IntSize,popup:IntSize,gap:Int,imeBottom:Int,systemTop:Int,systemBottom:Int):IntOffset {
    val left=gap
    val right=(window.width-popup.width-gap).coerceAtLeast(left)
    val top=systemTop+gap
    val bottom=(window.height-maxOf(imeBottom,systemBottom)-gap).coerceAtLeast(top)
    val maxY=(bottom-popup.height).coerceAtLeast(top)
    val below=anchor.bottom+gap
    val preferred=if(below+popup.height<=bottom)below else anchor.top-popup.height-gap
    return IntOffset((anchor.center.x-popup.width/2).coerceIn(left,right),preferred.coerceIn(top,maxY))
}
