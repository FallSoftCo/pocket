package co.fallsoft.pocket

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor

internal data class ImmersionSharedWipeMask(val center:Float,val feather:Float)

/** One physical reading path for all changed fragments in a paragraph.
 * Native placeholder bounds determine line bands and horizontal origins.
 * This is animation geometry, not a perception/comprehension threshold. */
internal data class ImmersionSharedWipeGeometry(
    val regions:Map<String,Rect>,val paragraphWidth:Float,val lineHeight:Float
){
    fun mask(key:String,localLineTop:Float,progress:Float,rtl:Boolean):ImmersionSharedWipeMask? {
        val rect=regions[key]?:return null
        if(regions.isEmpty()||paragraphWidth<=0f||lineHeight<=0f)return null
        val top=regions.values.minOf{it.top}
        val bottom=regions.values.maxOf{it.bottom}
        val lines=ceil((bottom-top)/lineHeight).toInt().coerceAtLeast(1)
        val line=floor((rect.top+localLineTop-top)/lineHeight).toInt().coerceIn(0,lines-1)
        val phase=immersionWipeLineProgress(progress,line,lines)
        val feather=(paragraphWidth*.12f).coerceIn(12f,48f)
        val frontier=-feather+(paragraphWidth+2f*feather)*phase
        val globalCenter=if(rtl)paragraphWidth-frontier else frontier
        return ImmersionSharedWipeMask(globalCenter-rect.left,feather)
    }
}
