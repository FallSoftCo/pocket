package co.fallsoft.pocket

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The same action in two aligned lanes: Italian first, an English gloss immediately below. */
@Composable
fun BilingualLabel(text:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=null,maxLines:Int=1,centered:Boolean=true){
    LaunchedEffect(text,PocketImmersion.enabled){PocketImmersion.offerLabel(text)}
    val target=PocketImmersion.label(text)
    Column(modifier,verticalArrangement=Arrangement.Center,horizontalAlignment=if(centered)Alignment.CenterHorizontally else Alignment.Start){
        Text(target,color=color,fontSize=fontSize,fontWeight=fontWeight,maxLines=maxLines,overflow=TextOverflow.Ellipsis)
        if(PocketImmersion.enabled&&PocketImmersion.supportEnabled&&target!=text)Text(text,color=color.copy(alpha=0.65f),fontSize=(fontSize.value*0.68f).coerceAtLeast(9f).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
