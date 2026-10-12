package co.fallsoft.pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.ActionMode
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
    private data class Slot(val target:String,val support:String,var selectedSupport:Boolean=false,
        var start:Int=0,var end:Int=0,var width:Int=0)
    private var slots=emptyList<Slot>()
    private var separators=emptyList<String>()
    private var elapsed=0L
    private var viewportWidth=1f
    private var policy=ImmersionReadingWave()
    private var touching=false
    private var nativeMenu=false
    private var originX=0f
    private var originY=0f
    private val additiveInk=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD)}
    var onInteractionHold:((Boolean)->Unit)?=null

    init {
        setTextIsSelectable(true)
        customSelectionActionModeCallback=object:ActionMode.Callback {
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {
                nativeMenu=true;reportHold();return true
            }
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem)=false
            override fun onDestroyActionMode(mode:ActionMode){nativeMenu=false;reportHold()}
        }
    }

    /** Returns false rather than fabricating correspondence for unaligned forms. */
    fun setAlignedForms(target:String,support:String):Boolean {
        fun tokens(value:String)=Regex("""\S+|\s+""").findAll(value).map{it.value}.toList()
        val a=tokens(target);val b=tokens(support)
        if(a.size!=b.size||a.indices.any{a[it].isBlank()!=b[it].isBlank()||
            (a[it].isBlank()&&a[it]!=b[it])})return false
        val words=mutableListOf<Slot>()
        val gaps=mutableListOf<String>()
        var gap=""
        a.indices.forEach { index ->
            if(a[index].isBlank())gap+=a[index]
            else {
                gaps+=gap;gap=""
                words+=Slot(a[index],b[index],width=ceil(maxOf(
                    paint.measureText(a[index]),paint.measureText(b[index]))).toInt())
            }
        }
        gaps+=gap
        slots=words;separators=gaps
        rebuildVisibleText()
        return true
    }

    private fun reportHold(){onInteractionHold?.invoke(touching||nativeMenu)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{touching=true;reportHold()}
        }
        val handled=super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{touching=false;reportHold()}
        }
        return handled
    }
    override fun onDetachedFromWindow() {
        touching=false;nativeMenu=false;reportHold()
        super.onDetachedFromWindow()
    }

    /**
     * Refresh visible semantics only when a frontier crosses a lexical slot.
     * Native selection keeps its exact underlying text throughout the gesture/menu.
     */
    fun present(timeMs:Long,screenWidthPx:Float,wave:ImmersionReadingWave=policy) {
        if(touching||nativeMenu)return
        elapsed=timeMs;viewportWidth=screenWidthPx.coerceAtLeast(1f);policy=wave
        val location=IntArray(2);getLocationInWindow(location)
        originX=location[0].toFloat()+totalPaddingLeft
        originY=location[1].toFloat()+totalPaddingTop
        val nativeLayout=layout
        var changed=false
        if(nativeLayout!=null)for(slot in slots) {
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
        val value=StringBuilder()
        for((index,slot) in slots.withIndex()) {
            value.append(separators[index])
            slot.start=value.length
            value.append(if(slot.selectedSupport)slot.support else slot.target)
            slot.end=value.length
        }
        value.append(separators.lastOrNull().orEmpty())
        val paragraph=SpannableString(value)
        for(slot in slots)paragraph.setSpan(object:ReplacementSpan(){
            override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
                fm?.let{paint.getFontMetricsInt(it)}
                return slot.width
            }
            override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
                if(slot.target==slot.support) {
                    canvas.drawText(slot.target,x,y.toFloat(),paint)
                    return
                }
                val frame=policy.frame(elapsed,
                    ((originY+top)/resources.displayMetrics.density).toDouble())
                val feather=(viewportWidth*.06f).coerceAtLeast(12f)
                val frontier=-feather+(viewportWidth+2*feather)*frame.progress-originX
                val left=frontier-feather
                val right=frontier+feather
                val supportLeft=frame.towardSupport
                fun drawForm(value:String,support:Boolean) {
                    // Entire holds bypass saveLayer. Only frontier-adjacent ink
                    // uses a complementary mask; unchanged words never dim.
                    val boundsLeft=x
                    val boundsRight=x+slot.width
                    val opaqueAtLeft=if(support)supportLeft else !supportLeft
                    if(boundsRight<=left||boundsLeft>=right) {
                        val visible=if(boundsRight<=left)opaqueAtLeft else !opaqueAtLeft
                        if(visible)canvas.drawText(value,x,y.toFloat(),paint)
                        return
                    }
                    val metrics=paint.fontMetrics
                    val layer=canvas.saveLayer(x,y+metrics.ascent,x+slot.width,y+metrics.descent,additiveInk)
                    canvas.drawText(value,x,y.toFloat(),paint)
                    val mask=Paint().apply {
                        shader=LinearGradient(left,0f,right,0f,
                            if(opaqueAtLeft)intArrayOf(-1,0x00ffffff)else intArrayOf(0x00ffffff,-1),
                            null,Shader.TileMode.CLAMP)
                        xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    canvas.drawRect(x,y+metrics.ascent,x+slot.width,y+metrics.descent,mask)
                    canvas.restoreToCount(layer)
                }
                // Add complementary premultiplied ink inside a transparent
                // group, then composite that group once over the background.
                // SRC_OVER between the two masks would dim identical glyphs
                // to 75% coverage at their midpoint.
                val metrics=paint.fontMetrics
                val blending=x<right&&x+slot.width>left
                val group=if(blending)canvas.saveLayer(x,y+metrics.ascent,x+slot.width,y+metrics.descent,null)else -1
                drawForm(slot.target,false);drawForm(slot.support,true)
                if(group>=0)canvas.restoreToCount(group)
            }
        },slot.start,slot.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setText(paragraph,BufferType.SPANNABLE)
    }
}
