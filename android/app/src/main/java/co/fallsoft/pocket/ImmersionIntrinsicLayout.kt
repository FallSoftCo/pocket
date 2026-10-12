package co.fallsoft.pocket

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Menus ask for text intrinsics before measuring their children. Answer using
 * native shaping rather than asking the subcomposed animation layout. */
internal fun immersionIntrinsicLayout(
    measurer:TextMeasurer,forms:List<AnnotatedString>,style:TextStyle,
    maxLines:Int,overflow:TextOverflow
):Modifier = object:LayoutModifier {
    private fun width()=forms.maxOfOrNull{
        measurer.measure(immersionDrawText(it),style=style,softWrap=false,maxLines=maxLines).size.width
    }?:0
    private fun height(width:Int)=forms.maxOfOrNull{
        measurer.measure(immersionDrawText(it),style=style,maxLines=maxLines,overflow=overflow,
            constraints=Constraints(maxWidth=width.coerceAtLeast(1))).size.height
    }?:0
    override fun MeasureScope.measure(measurable:Measurable,constraints:Constraints):MeasureResult{
        val child=measurable.measure(constraints)
        return layout(child.width,child.height){child.placeRelative(0,0)}
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable:IntrinsicMeasurable,height:Int)=width()
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable:IntrinsicMeasurable,height:Int)=width()
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable:IntrinsicMeasurable,width:Int)=height(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable:IntrinsicMeasurable,width:Int)=height(width)
}
