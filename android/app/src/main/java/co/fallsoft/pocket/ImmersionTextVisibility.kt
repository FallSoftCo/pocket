package co.fallsoft.pocket

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.TextLayoutResult

/** Clipped native glyph geometry, not composition/prefetch presence, permits a cue. */
internal fun immersionVisibleText(layout:TextLayoutResult?,coordinates:LayoutCoordinates?,wholeText:Boolean=true):Pair<Set<String>,Boolean> {
    if(layout==null||coordinates==null||!coordinates.isAttached)return emptySet<String>() to false
    val viewport=coordinates.boundsInWindow()
    if(viewport.width<=0f||viewport.height<=0f)return emptySet<String>() to false
    val text=layout.layoutInput.text
    fun readable(start:Int,end:Int):Boolean {
        if(start>=end||start>=text.length)return false
        // Reject clipped phrase bands before walking individual glyphs. A long
        // paragraph's offscreen spans must not turn scrolling into a full-text scan.
        val first=layout.getLineForOffset(start.coerceAtLeast(0))
        val last=layout.getLineForOffset((end-1).coerceAtMost(text.length-1))
        val top=coordinates.localToWindow(Offset(0f,layout.getLineTop(first))).y
        val bottom=coordinates.localToWindow(Offset(0f,layout.getLineBottom(last))).y
        if(maxOf(top,bottom)<=viewport.top||minOf(top,bottom)>=viewport.bottom)return false
        var total=0;var visible=0
        for(offset in start.coerceAtLeast(0) until end.coerceAtMost(text.length)){
            if(text[offset].isWhitespace())continue
            total++
            val line=layout.getLineForOffset(offset)
            if(line>=layout.lineCount||offset>=layout.getLineEnd(line,visibleEnd=true))continue
            val box=layout.getBoundingBox(offset)
            val a=coordinates.localToWindow(Offset(box.left,box.top))
            val b=coordinates.localToWindow(Offset(box.right,box.bottom))
            val window=Rect(minOf(a.x,b.x),minOf(a.y,b.y),maxOf(a.x,b.x),maxOf(a.y,b.y))
            if(window.width<=0f||window.height<=0f)continue
            val overlap=window.intersect(viewport)
            if(overlap.width>0f&&overlap.height>0f&&overlap.width*overlap.height>=window.width*window.height*.75f)visible++
        }
        // Tuning prevents a clipped sliver from acquiring a lease. It is not a
        // psychophysical threshold for recognition or comprehension.
        return total>0&&visible>=minOf(3,total)&&visible*2>=total
    }
    val ids=text.getStringAnnotations("immersion-reserve",0,text.length)
        .filter{readable(it.start,it.end)}.map{it.item}.toSet()
    return ids to (wholeText&&readable(0,text.length))
}
