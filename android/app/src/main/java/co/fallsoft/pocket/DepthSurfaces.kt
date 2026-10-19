package co.fallsoft.pocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Retain decorative paint in its own render layer. Editor, list and immersion
 * invalidations must not rerun full-window gradient/texture shaders. The layer
 * contains no interactive content or animation state and follows window size. */
@androidx.compose.runtime.Composable
internal fun NextCompDepthRoot(content:@androidx.compose.runtime.Composable ()->Unit){
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()){
        androidx.compose.foundation.layout.Box(Modifier.matchParentSize()
            .graphicsLayer(compositingStrategy=androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
            .nextCompBackdrop())
        androidx.compose.material3.Surface(Modifier.fillMaxSize(),color=Color.Transparent,contentColor=Paper,content=content)
    }
}

/** Stationary screen-space texture. The small tile and brushes are rebuilt only
 * when the draw cache (size/density) changes, never from the immersion clock.
 * Blackout owns a separate opaque native window and does not use this surface. */
internal fun Modifier.nextCompBackdrop():Modifier=drawWithCache {
    val spacing=18.dp.toPx().toInt().coerceAtLeast(1)
    val tile=Bitmap.createBitmap(spacing,spacing,Bitmap.Config.ARGB_8888)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(47,56,68)}
    Canvas(tile).drawCircle(spacing*.5f,spacing*.5f,.6.dp.toPx(),paint)
    val dots=ShaderBrush(ImageShader(tile.asImageBitmap(),TileMode.Repeated,TileMode.Repeated))
    val ground=Brush.verticalGradient(listOf(Color(0xff0c1016),Color(0xff05070a)))
    val light=Brush.radialGradient(listOf(Color(0x101c304a),Color.Transparent),Offset(size.width*.65f,0f),size.maxDimension.coerceAtLeast(1f))
    onDrawBehind{drawRect(ground);drawRect(dots);drawRect(light)}
}

/** Prelit bevels shared by both backends. No runtime drop-shadow or blur layer
 * wraps editable content. Geometry, touch targets and idle hue stay unchanged. */
internal fun Modifier.depthSurface(base:Color,shape:Shape,elevation:Dp=3.dp):Modifier=
    clip(shape).drawWithCache {
        val finish=Brush.verticalGradient(listOf(Color.White.copy(alpha=.045f),Color.Transparent,Color.Black.copy(alpha=.12f)))
        val depth=(elevation.value/6f).coerceIn(0f,1f)
        val edge=(.5f+.5f*depth).dp.toPx()
        val high=Color.White.copy(alpha=.08f+.04f*depth)
        val low=Color.Black.copy(alpha=.22f)
        onDrawBehind{
            drawRect(base);drawRect(finish)
            drawLine(high,Offset(0f,edge/2),Offset(size.width,edge/2),edge)
            drawLine(high,Offset(edge/2,0f),Offset(edge/2,size.height),edge)
            drawLine(low,Offset(0f,size.height-edge/2),Offset(size.width,size.height-edge/2),edge)
            drawLine(low,Offset(size.width-edge/2,0f),Offset(size.width-edge/2,size.height),edge)
        }
    }
