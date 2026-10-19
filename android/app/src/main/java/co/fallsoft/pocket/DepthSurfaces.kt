package co.fallsoft.pocket

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.sqrt

/** One window-owned decorative raster, never a conversation/text cache. Window
 * metrics stay stable across IME insets; 64px buckets avoid resize churn. The
 * backing bitmap is capped at16MiB and has no animation or editor-state inputs. */
@Composable internal fun NextCompDepthRoot(content:@Composable ()->Unit){
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    val metrics=context.resources.displayMetrics
    val bounds=if(android.os.Build.VERSION.SDK_INT>=30)context.nextCompActivity()?.windowManager?.currentWindowMetrics?.bounds else null
    fun bucket(value:Int)=((value.coerceAtLeast(1)+63)/64)*64
    val width=bucket(bounds?.width()?:metrics.widthPixels)
    val height=bucket(bounds?.height()?:metrics.heightPixels)
    val image=remember(width,height,metrics.density,configuration.orientation){depthBackdropRaster(width,height,metrics.density).asImageBitmap().apply{prepareToDraw()}}
    Box(Modifier.fillMaxSize()){
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()){
            drawImage(image,dstSize=IntSize(width,height),filterQuality=if(image.width==width&&image.height==height)FilterQuality.None else FilterQuality.Low)
        }
        Surface(Modifier.fillMaxSize(),color=Color.Transparent,contentColor=Paper,content=content)
    }
}

private fun depthBackdropRaster(width:Int,height:Int,density:Float):Bitmap{
    val maxPixels=4*1024*1024
    val scale=sqrt((maxPixels.toDouble()/(width.toDouble()*height)).coerceAtMost(1.0)).toFloat()
    val w=(width*scale).toInt().coerceAtLeast(1);val h=(height*scale).toInt().coerceAtLeast(1)
    val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    check(image.allocationByteCount<=16*1024*1024)
    val canvas=Canvas(image);canvas.scale(w.toFloat()/width,h.toFloat()/height)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(0xff0c1016.toInt(),0xff05070a.toInt()),null,Shader.TileMode.CLAMP)
    canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    val spacing=(18*density).toInt().coerceAtLeast(1)
    val tile=Bitmap.createBitmap(spacing,spacing,Bitmap.Config.ARGB_8888)
    Canvas(tile).drawCircle(spacing*.5f,spacing*.5f,.6f*density,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(47,56,68)})
    paint.shader=BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    paint.shader=RadialGradient(width*.65f,0f,maxOf(width,height).toFloat(),intArrayOf(0x101c304a,0),null,Shader.TileMode.CLAMP)
    canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    paint.shader=null;tile.recycle();image.setHasAlpha(false)
    return image
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
