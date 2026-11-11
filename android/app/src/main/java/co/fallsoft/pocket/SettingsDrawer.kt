package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Controls reserve a whole label, never individual vocabulary tiles. */
internal val LocalDrawerLabels=staticCompositionLocalOf{false}

private class DrawerEntry(val close:()->Unit,val content:@Composable ColumnScope.()->Unit)
private class DrawerHostState { var current by mutableStateOf<DrawerEntry?>(null) }
private val LocalDrawerHost=staticCompositionLocalOf<DrawerHostState?>{null}

/** Material sheet in the Activity window: hardware voice keys and drafts keep
 * their owner. Destinations register only while mounted; stale closures cannot
 * survive a profile/conversation change. */
@OptIn(ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun SettingsDrawerHost(content:@Composable ()->Unit){
    val host=remember{DrawerHostState()}
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={value->
        if(value==SheetValue.Hidden)host.current?.close()
        true
    })
    val state=rememberBottomSheetScaffoldState(bottomSheetState=sheet)
    val entry=host.current
    var retained by remember{mutableStateOf<DrawerEntry?>(null)}
    SideEffect{if(entry!=null)retained=entry}
    val visible=entry?:retained
    val maxHeight=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.85f).dp
    LaunchedEffect(entry){if(entry!=null)sheet.expand()else{sheet.hide();retained=null}}
    BackHandler(entry!=null){entry?.close()}
    CompositionLocalProvider(LocalDrawerHost provides host){
        BottomSheetScaffold(scaffoldState=state,sheetPeekHeight=0.dp,sheetMaxWidth=640.dp,
            sheetSwipeEnabled=entry!=null,sheetContainerColor=Panel,sheetContentColor=Paper,
            sheetShape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),containerColor=Color.Transparent,
            sheetDragHandle={if(visible!=null)BottomSheetDefaults.DragHandle()},
            sheetContent={Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).navigationBarsPadding().semantics{testTagsAsResourceId=true}){
                if(visible!=null)visible.content(this)else Spacer(Modifier.height(1.dp))
            }}){
            Box(Modifier.fillMaxSize()){
                Box(Modifier.fillMaxSize().then(if(entry!=null)Modifier.clearAndSetSemantics{} else Modifier)){content()}
                if(entry!=null)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.45f))
                    .clickable(onClickLabel="Close settings",onClick=entry.close))
            }
        }
    }
}

@Composable internal fun SettingsDrawer(open:Boolean,onClose:()->Unit,title:String="Settings",
    content:@Composable ColumnScope.()->Unit){
    if(!open)return
    val host=checkNotNull(LocalDrawerHost.current){"SettingsDrawer requires the Activity drawer host"}
    val latestClose by rememberUpdatedState(onClose)
    val latestContent by rememberUpdatedState(content)
    val latestTitle by rememberUpdatedState(title)
    val entry=remember{DrawerEntry({latestClose()}){
        CompositionLocalProvider(LocalDrawerLabels provides true){
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){
                BilingualLabel(latestTitle,Modifier.weight(1f),centered=false,fontSize=22.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
                TextButton({latestClose()},Modifier.heightIn(min=48.dp)){BilingualLabel("Done",maxLines=2)}
            }
            Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(rememberScrollState())
                .padding(start=20.dp,end=20.dp,bottom=24.dp).testTag("settings-drawer-content"),
                verticalArrangement=Arrangement.spacedBy(12.dp),content=latestContent)
        }
    }}
    DisposableEffect(open,host,entry){
        if(open)host.current=entry
        onDispose{if(host.current===entry)host.current=null}
    }
}

@Composable internal fun DrawerSection(title:String,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        BilingualLabel(title,Modifier.padding(start=12.dp,top=8.dp,bottom=4.dp),centered=false,
            color=Muted,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=2)
        Surface(color=Ink.copy(alpha=.55f),shape=RoundedCornerShape(18.dp)){
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
        }
    }
}

@Composable internal fun DrawerAction(label:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier){
    TextButton(onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=48.dp),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp)){
        BilingualLabel(label,Modifier.weight(1f),centered=false,maxLines=3,color=if(enabled)Paper else Muted)
    }
}

@Composable internal fun DrawerDisclosure(title:String,content:@Composable ColumnScope.()->Unit){
    var expanded by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth()){
        TextButton({expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
            contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp)){
            BilingualLabel(title,Modifier.weight(1f),centered=false,maxLines=3)
            SymbolIcon(if(expanded)"ExpandLess" else "ExpandMore",null,Modifier.padding(start=12.dp).size(20.dp))
        }
        if(expanded)Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
