package co.fallsoft.pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.MetricAffectingSpan
import android.view.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.view.ActionMode
import android.view.DragEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.ceil

/**
 * Native selectable paragraph foundation. It deliberately accepts only verified
 * position-preserving lexical forms; richer content must retain its own adapter.
 * The screen owner supplies time. This view owns no frame loop or background timer.
 */
internal class NativeImmersionParagraphView(context:Context):TextView(context) {
    private data class InkForm(val value:AnnotatedString,val layout:StaticLayout,val width:Int)
    private data class Slot(val target:InkForm,val support:InkForm,var selectedSupport:Boolean=false,
        var start:Int=0,var end:Int=0,var width:Int=0,
        var drawX:Float=Float.NaN,var drawTop:Int=0,var drawBaseline:Int=0)
    private var slots=emptyList<Slot>()
    private var separators=emptyList<AnnotatedString>()
    private var elapsed=0L
    private var viewportWidth=1f
    private var policy=ImmersionReadingWave()
    private var touching=false
    private var nativeMenu=false
    private var userSelection=false
    private var nativeDrag=false
    private var updatingText=false
    private var pendingForms:Pair<List<Slot>,List<AnnotatedString>>?=null
    private var originX=0f
    private var originY=0f
    private val additiveInk=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD)}
    var onInteractionHold:((Boolean)->Unit)?=null
    var onLink:((LinkAnnotation)->Unit)?=null
    private var downX=0f
    private var downY=0f
    private var downTime=0L
    private var cancelledTap=false
    private var downLink:LinkAnnotation?=null
    private var menuAtDown=false

    init {
        setTextIsSelectable(true)
        customSelectionActionModeCallback=object:ActionMode.Callback {
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {
                nativeMenu=true;reportHold();return true
            }
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem)=false
            override fun onDestroyActionMode(mode:ActionMode){nativeMenu=false;userSelection=false;reportHold()}
        }
    }

    /** Returns false rather than fabricating correspondence for unaligned forms. */
    fun setAlignedForms(target:String,support:String):Boolean =
        setAlignedForms(AnnotatedString(target),AnnotatedString(support))

    fun setAlignedForms(target:AnnotatedString,support:AnnotatedString):Boolean {
        val marked=listOf(target,support).any{
            it.getStringAnnotations("immersion-reserve",0,it.length).isNotEmpty()
        }
        if(marked&&immersionSharedRuns(listOf(target,support))==null)return false
        // Unsupported typography must remain on the rich-content fallback,
        // never silently lose its style in the native candidate.
        if(listOf(target,support).any{form->form.spanStyles.any { range ->
            val style=range.item
            style.baselineShift!=null||style.shadow!=null||style.textGeometricTransform!=null||
                (style.fontFamily!=null&&style.fontFamily !in listOf(FontFamily.Default,
                    FontFamily.SansSerif,FontFamily.Serif,FontFamily.Cursive,FontFamily.Monospace))
        }})return false
        fun tokens(value:AnnotatedString)=Regex("""\S+|\s+""").findAll(value.text).map{
            value.subSequence(it.range.first,it.range.last+1)
        }.toList()
        val a=tokens(target);val b=tokens(support)
        if(a.size!=b.size||a.indices.any{a[it].text.isBlank()!=b[it].text.isBlank()||
            (a[it].text.isBlank()&&(a[it].text!=b[it].text||a[it].spanStyles!=b[it].spanStyles))})return false
        val words=mutableListOf<Slot>()
        val gaps=mutableListOf<AnnotatedString>()
        var gap=AnnotatedString("")
        a.indices.forEach { index ->
            if(a[index].text.isBlank())gap=androidx.compose.ui.text.buildAnnotatedString{append(gap);append(a[index])}
            else {
                gaps+=gap;gap=AnnotatedString("")
                val first=ink(a[index]);val second=ink(b[index])
                words+=Slot(first,second,width=maxOf(first.width,second.width))
            }
        }
        gaps+=gap
        if(touching||nativeMenu||userSelection||nativeDrag)pendingForms=words to gaps
        else {slots=words;separators=gaps;rebuildVisibleText()}
        return true
    }

    /** Native shaping owns style widths and glyph painting, including code font. */
    private fun nativeStyled(value:AnnotatedString):SpannableString {
        val styled=SpannableString(value.text)
        for(range in value.spanStyles) {
            val style=range.item
            fun apply(span:Any){if(range.end>range.start)
                styled.setSpan(span,range.start,range.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
            style.fontWeight?.let { weight ->
                apply(object:MetricAffectingSpan() {
                    private fun update(tp:TextPaint) {
                        tp.typeface=android.graphics.Typeface.create(tp.typeface,weight.weight,
                            tp.typeface?.isItalic==true)
                    }
                    override fun updateMeasureState(tp:TextPaint)=update(tp)
                    override fun updateDrawState(tp:TextPaint)=update(tp)
                })
            }
            if(style.fontStyle==FontStyle.Italic)apply(StyleSpan(android.graphics.Typeface.ITALIC))
            when(style.fontFamily) {
                FontFamily.Monospace->apply(TypefaceSpan("monospace"))
                FontFamily.SansSerif->apply(TypefaceSpan("sans-serif"))
                FontFamily.Serif->apply(TypefaceSpan("serif"))
                FontFamily.Cursive->apply(TypefaceSpan("cursive"))
                else->{}
            }
            if(style.fontSize.isSp)apply(AbsoluteSizeSpan(
                (style.fontSize.value*resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(1)))
            else if(style.fontSize.isEm)apply(RelativeSizeSpan(style.fontSize.value))
            if(style.letterSpacing.isSp||style.letterSpacing.isEm)apply(object:MetricAffectingSpan() {
                private fun update(tp:TextPaint) {
                    tp.letterSpacing=if(style.letterSpacing.isEm)style.letterSpacing.value
                        else style.letterSpacing.value*resources.displayMetrics.scaledDensity/tp.textSize
                }
                override fun updateMeasureState(tp:TextPaint)=update(tp)
                override fun updateDrawState(tp:TextPaint)=update(tp)
            })
            if(style.color!=Color.Unspecified)apply(ForegroundColorSpan(style.color.toArgb()))
            if(style.background!=Color.Unspecified)apply(BackgroundColorSpan(style.background.toArgb()))
            if(style.textDecoration?.contains(TextDecoration.Underline)==true)apply(UnderlineSpan())
            if(style.textDecoration?.contains(TextDecoration.LineThrough)==true)apply(StrikethroughSpan())
        }
        return styled
    }
    private fun ink(value:AnnotatedString):InkForm {
        val styled=nativeStyled(value)
        val nativePaint=TextPaint(paint).apply {color=currentTextColor}
        val width=ceil(Layout.getDesiredWidth(styled,nativePaint).toDouble()).toInt().coerceAtLeast(1)
        val shaped=StaticLayout.Builder.obtain(styled,0,styled.length,nativePaint,width)
            .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return InkForm(value,shaped,width)
    }

    private fun dispatch(link:LinkAnnotation) {
        onLink?.invoke(link)?:link.linkInteractionListener?.onClick(link)
    }
    private fun reportHold() {
        val held=nativeMenu||userSelection||nativeDrag
        if(!held&&!touching)pendingForms?.let { next ->
            pendingForms=null
            slots=next.first;separators=next.second;rebuildVisibleText()
        }
        onInteractionHold?.invoke(held)
        invalidate()
    }
    override fun onSelectionChanged(start:Int,end:Int) {
        super.onSelectionChanged(start,end)
        if(updatingText)return
        if(start>=0&&end>start&&(touching||nativeMenu||userSelection))userSelection=true
        else if(start==end)userSelection=false
        reportHold()
    }
    override fun onDragEvent(event:DragEvent):Boolean {
        when(event.action) {
            DragEvent.ACTION_DRAG_STARTED->{nativeDrag=true;reportHold()}
            DragEvent.ACTION_DRAG_ENDED->{nativeDrag=false;reportHold()}
        }
        val handled=super.onDragEvent(event)
        // Observe end-of-drag even for a read-only source. Drops retain native
        // handling; this observer neither inserts nor forwards dragged content.
        return if(event.action==DragEvent.ACTION_DRAG_STARTED)true else handled
    }

    /** Actual drawn glyph geometry excludes the reserved trailing blank region. */
    private fun linkAt(x:Float,y:Float):LinkAnnotation? {
        val localX=x-totalPaddingLeft+scrollX
        val localY=y-totalPaddingTop+scrollY
        for(slot in slots) {
            if(!slot.drawX.isFinite())continue
            val form=if(slot.selectedSupport)slot.support else slot.target
            val top=slot.drawBaseline-form.layout.getLineBaseline(0)
            if(localY<top||localY>=top+form.layout.height||
                localX<slot.drawX||localX>=slot.drawX+form.width)continue
            val position=localX-slot.drawX
            val caret=form.layout.getOffsetForHorizontal(0,position)
            val offset=listOf(caret,caret-1).firstOrNull { index ->
                if(index !in 0 until form.value.length)false else {
                    val a=form.layout.getPrimaryHorizontal(index)
                    val b=form.layout.getPrimaryHorizontal(index+1)
                    position>=minOf(a,b)&&position<maxOf(a,b)
                }
            }?:continue
            return form.value.getLinkAnnotations(offset,offset+1).firstOrNull()?.item
        }
        return null
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                touching=true;downX=event.x;downY=event.y;downTime=event.eventTime
                cancelledTap=false;menuAtDown=nativeMenu;downLink=linkAt(event.x,event.y);reportHold()
            }
            MotionEvent.ACTION_MOVE->{
                val slop=ViewConfiguration.get(context).scaledTouchSlop
                if(kotlin.math.abs(event.x-downX)>slop||kotlin.math.abs(event.y-downY)>slop)
                    cancelledTap=true
            }
            MotionEvent.ACTION_CANCEL->cancelledTap=true
        }
        val handled=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP&&!cancelledTap&&!menuAtDown&&!nativeMenu&&!userSelection&&
            event.eventTime-downTime<ViewConfiguration.getLongPressTimeout()) {
            downLink?.let(::dispatch)
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{touching=false;downLink=null;reportHold()}
        }
        return handled
    }
    override fun onDetachedFromWindow() {
        touching=false;nativeMenu=false;userSelection=false;nativeDrag=false
        pendingForms=null;reportHold()
        super.onDetachedFromWindow()
    }

    /**
     * Refresh visible semantics only when a frontier crosses a lexical slot.
     * Native selection keeps its exact underlying text throughout the gesture/menu.
     */
    fun present(timeMs:Long,screenWidthPx:Float,wave:ImmersionReadingWave=policy) {
        if(nativeMenu||userSelection||nativeDrag)return
        elapsed=timeMs;viewportWidth=screenWidthPx.coerceAtLeast(1f);policy=wave
        val location=IntArray(2);getLocationInWindow(location)
        originX=location[0].toFloat()+totalPaddingLeft
        originY=location[1].toFloat()+totalPaddingTop
        val nativeLayout=layout
        var changed=false
        // Keep Android pointer offsets stable until release; ink still follows the clock.
        if(!touching&&nativeLayout!=null)for(slot in slots) {
            if(slot.start>=text.length)continue
            val line=nativeLayout.getLineForOffset(slot.start)
            val frame=policy.frame(elapsed,
                ((originY+nativeLayout.getLineTop(line))/resources.displayMetrics.density).toDouble())
            val center=(originX+nativeLayout.getPrimaryHorizontal(slot.start)+slot.width/2)/viewportWidth
            val selected=frame.supportCoverage(center)>=.5f
            if(selected!=slot.selectedSupport){slot.selectedSupport=selected;changed=true}
        }
        if(changed)rebuildVisibleText()
        invalidate()
    }

    private fun rebuildVisibleText() {
        val value=SpannableStringBuilder()
        for((index,slot) in slots.withIndex()) {
            value.append(nativeStyled(separators[index]))
            slot.start=value.length
            value.append((if(slot.selectedSupport)slot.support else slot.target).value.text)
            slot.end=value.length
        }
        separators.lastOrNull()?.let{value.append(nativeStyled(it))}
        val paragraph=SpannableString(value)
        for(slot in slots)paragraph.setSpan(object:ReplacementSpan(){
            override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
                fm?.let {
                    paint.getFontMetricsInt(it)
                    it.ascent=minOf(it.ascent,slot.target.layout.getLineAscent(0),slot.support.layout.getLineAscent(0))
                    it.descent=maxOf(it.descent,slot.target.layout.getLineDescent(0),slot.support.layout.getLineDescent(0))
                    it.top=minOf(it.top,it.ascent);it.bottom=maxOf(it.bottom,it.descent)
                }
                return slot.width
            }
            override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
                slot.drawX=x;slot.drawTop=top;slot.drawBaseline=y
                fun paintForm(form:InkForm) {
                    val saved=canvas.save()
                    canvas.translate(x,y.toFloat()-form.layout.getLineBaseline(0))
                    form.layout.draw(canvas)
                    canvas.restoreToCount(saved)
                }
                if(nativeMenu||userSelection||nativeDrag) {
                    // Selection displays exactly the underlying copy snapshot,
                    // even if the gesture began while the wave was crossing it.
                    paintForm(if(slot.selectedSupport)slot.support else slot.target)
                    return
                }
                if(slot.target.value==slot.support.value) {
                    paintForm(slot.target)
                    return
                }
                val frame=policy.frame(elapsed,
                    ((originY+top)/resources.displayMetrics.density).toDouble())
                val feather=(viewportWidth*.06f).coerceAtLeast(12f)
                val frontier=-feather+(viewportWidth+2*feather)*frame.progress-originX
                val left=frontier-feather
                val right=frontier+feather
                val supportLeft=frame.towardSupport
                fun drawForm(value:InkForm,support:Boolean) {
                    // Entire holds bypass saveLayer. Only frontier-adjacent ink
                    // uses a complementary mask; unchanged words never dim.
                    val boundsLeft=x
                    val boundsRight=x+slot.width
                    val opaqueAtLeft=if(support)supportLeft else !supportLeft
                    if(boundsRight<=left||boundsLeft>=right) {
                        val visible=if(boundsRight<=left)opaqueAtLeft else !opaqueAtLeft
                        if(visible)paintForm(value)
                        return
                    }
                    val ascent=minOf(paint.fontMetrics.ascent,slot.target.layout.getLineAscent(0).toFloat(),
                        slot.support.layout.getLineAscent(0).toFloat())
                    val descent=maxOf(paint.fontMetrics.descent,slot.target.layout.getLineDescent(0).toFloat(),
                        slot.support.layout.getLineDescent(0).toFloat())
                    val layer=canvas.saveLayer(x,y+ascent,x+slot.width,y+descent,additiveInk)
                    paintForm(value)
                    val mask=Paint().apply {
                        shader=LinearGradient(left,0f,right,0f,
                            if(opaqueAtLeft)intArrayOf(-1,0x00ffffff)else intArrayOf(0x00ffffff,-1),
                            null,Shader.TileMode.CLAMP)
                        xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    canvas.drawRect(x,y+ascent,x+slot.width,y+descent,mask)
                    canvas.restoreToCount(layer)
                }
                // Add complementary premultiplied ink inside a transparent
                // group, then composite that group once over the background.
                // SRC_OVER between the two masks would dim identical glyphs
                // to 75% coverage at their midpoint.
                val ascent=minOf(paint.fontMetrics.ascent,slot.target.layout.getLineAscent(0).toFloat(),
                    slot.support.layout.getLineAscent(0).toFloat())
                val descent=maxOf(paint.fontMetrics.descent,slot.target.layout.getLineDescent(0).toFloat(),
                    slot.support.layout.getLineDescent(0).toFloat())
                val blending=x<right&&x+slot.width>left
                val group=if(blending)canvas.saveLayer(x,y+ascent,x+slot.width,y+descent,null)else -1
                drawForm(slot.target,false);drawForm(slot.support,true)
                if(group>=0)canvas.restoreToCount(group)
            }
        },slot.start,slot.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for(slot in slots) {
            val form=if(slot.selectedSupport)slot.support else slot.target
            for(link in form.value.getLinkAnnotations(0,form.value.length)) {
                paragraph.setSpan(object:ClickableSpan() {
                    override fun onClick(widget:android.view.View){dispatch(link.item)}
                    override fun updateDrawState(ds:TextPaint){}
                },slot.start+link.start,slot.start+link.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        updatingText=true
        try{setText(paragraph,BufferType.SPANNABLE)}finally{updatingText=false}
    }
}
