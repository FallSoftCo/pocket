package co.fallsoft.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BilingualMessage(id:String,original:String,modifier:Modifier=Modifier){
    LaunchedEffect(id,original,PocketImmersion.enabled){PocketImmersion.offer(id,original)}
    val target=PocketImmersion.display(id,original)
    if(!PocketImmersion.enabled||!PocketImmersion.supportEnabled||target==original){RichText(target);return}
    val units=remember(original,target){bilingualUnits(original,target)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(10.dp)){
        units.forEachIndexed{index,unit->
            RichText(unit.target)
            if(unit.original!=null){
                var expanded by remember(id,index,unit.original){mutableStateOf(false)}
                Column(Modifier.clickable{expanded=!expanded}.semantics{contentDescription=if(expanded)"English gloss; tap to collapse" else "English gloss; tap to expand"}){
                    MarkdownPreview(unit.original,color=Muted,fontSize=12.sp,lineHeight=17.sp,maxLines=if(expanded)Int.MAX_VALUE else 2)
                }
            }
        }
    }
}
/** Explain the command's action while executable text and copied text remain untouched. */
@Composable
fun BilingualCommandGloss(command:String,modifier:Modifier=Modifier){
    if(!PocketImmersion.enabled)return
    val meaning=remember(command){ImmersionCommands.meaning(command)}?:return
    Column(modifier){
        Text(meaning.first,color=Mint,fontSize=12.sp)
        if(PocketImmersion.supportEnabled)Text(meaning.second,color=Muted,fontSize=10.sp)
    }
}
