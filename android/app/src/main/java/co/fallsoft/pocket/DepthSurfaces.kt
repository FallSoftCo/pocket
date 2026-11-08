package co.fallsoft.pocket

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.sqrt

/** Stationary, batched vector texture. At most256KiB of cached positions, no
 * full-window image, shader or blur. Cache construction depends only on size
 * and density; recording one native point batch needs no per-frame point loop. */
@Composable internal fun NextCompDepthRoot(content:@Composable ()->Unit){
    Surface(Modifier.fillMaxSize(),color=Color(0xff090d12),contentColor=Paper){
        Box(Modifier.fillMaxSize()){
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize().drawWithCache{
                val maxPoints=32768
                var spacing=18.dp.toPx().coerceAtLeast(1f)
                val estimated=ceil(size.width/spacing).toLong()*ceil(size.height/spacing).toLong()
                if(estimated>maxPoints)spacing*=sqrt(estimated.toFloat()/maxPoints)*1.05f
                val columns=ceil(size.width/spacing).toInt().coerceAtLeast(0)
                val rows=ceil(size.height/spacing).toInt().coerceAtLeast(0)
                val count=(columns.toLong()*rows).coerceAtMost(maxPoints.toLong()).toInt()
                val points=FloatArray(count*2)
                for(index in 0 until count){points[index*2]=(index%columns+.5f)*spacing;points[index*2+1]=(index/columns+.5f)*spacing}
                val paint=Paint().apply{color=android.graphics.Color.rgb(47,56,68);strokeWidth=1.dp.toPx().toInt().coerceAtLeast(1).toFloat();strokeCap=Paint.Cap.BUTT}
                onDrawBehind{drawIntoCanvas{it.nativeCanvas.drawPoints(points,paint)}}
            })
            content()
        }
    }
}

/** Prelit opaque faces and clipped bevels; no blur/shadow effect wraps an editor. */
internal fun Modifier.depthSurface(base:Color,shape:Shape,elevation:Dp=3.dp):Modifier=
    clip(shape).drawWithCache {
        val finish=Brush.verticalGradient(listOf(Color.White.copy(alpha=.045f).compositeOver(base),base,Color.Black.copy(alpha=.12f).compositeOver(base)))
        val depth=(elevation.value/6f).coerceIn(0f,1f);val edge=(.5f+.5f*depth).dp.toPx()
        val high=Color.White.copy(alpha=.08f+.04f*depth);val low=Color.Black.copy(alpha=.22f)
        onDrawBehind{
            drawRect(finish)
            drawLine(high,Offset(0f,edge/2),Offset(size.width,edge/2),edge)
            drawLine(high,Offset(edge/2,0f),Offset(edge/2,size.height),edge)
            drawLine(low,Offset(0f,size.height-edge/2),Offset(size.width,size.height-edge/2),edge)
            drawLine(low,Offset(size.width-edge/2,0f),Offset(size.width-edge/2,size.height),edge)
        }
    }
