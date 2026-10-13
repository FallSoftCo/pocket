package co.fallsoft.pocket

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native selection foundation qualification, not an integrated immersion release. */
@RunWith(AndroidJUnit4::class)
class NativeReservedWordSelectionTest {
    @Test fun reservedWordRetainsNativeLongPressSelectionAndWholeParagraphCopy() {
        val i=InstrumentationRegistry.getInstrumentation()
        val c=i.targetContext
        val device=UiDevice.getInstance(i)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        check(android.os.Build.VERSION.SDK_INT>=29)
        device.executeShellCommand("pm grant "+c.packageName+" android.permission.POST_NOTIFICATIONS")
        c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:18880").commit()
        i.runOnMainSync{Pocket.init(c)}
        val forms=listOf("Permessi completi per i nuovi incarichi", "Permissions full per i new tasks")
        val helped=java.util.concurrent.atomic.AtomicInteger(0)
        val longPresses=java.util.concurrent.atomic.AtomicInteger(0)
        val inputTrace=org.json.JSONArray()
        java.io.File(c.cacheDir,"native-rich-input-trace.json").delete()
        val lastHelp=java.util.concurrent.atomic.AtomicReference("")
        val richForms=forms.map { phrase ->
            androidx.compose.ui.text.buildAnnotatedString {
                append(phrase)
                addStyle(androidx.compose.ui.text.SpanStyle(
                    fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                    color=androidx.compose.ui.graphics.Color(0xff7ffff0)),0,phrase.substringBefore(" ").length)
                val code=phrase.indexOf("per i")
                addStyle(androidx.compose.ui.text.SpanStyle(
                    fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,
                    background=androidx.compose.ui.graphics.Color(0xff292a30)),code,code+5)
                val last=phrase.lastIndexOf(" ")+1
                addStyle(androidx.compose.ui.text.SpanStyle(
                    fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontStyle=androidx.compose.ui.text.font.FontStyle.Italic),last,phrase.length)
                addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("native-fixture-help",
                    linkInteractionListener=androidx.compose.ui.text.LinkInteractionListener {
                        helped.incrementAndGet();lastHelp.set(phrase)
                    }),0,phrase.length)
            }
        }
        val app=c.applicationContext as Application
        var text:NativeImmersionParagraphView?=null
        val interactionHold=java.util.concurrent.atomic.AtomicBoolean(false)
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity:Activity,state:Bundle?) {
                if(activity !is MainActivity)return
                val view=NativeImmersionParagraphView(activity).apply {
                    textSize=17f
                    setTextColor(Color.WHITE)
                    typeface=ResourcesCompat.getFont(c,R.font.inter_regular)
                    setTextIsSelectable(true)
                    onInteractionHold={interactionHold.set(it)}
                    setOnLongClickListener{longPresses.incrementAndGet();false}
                    check(setAlignedForms(richForms[0],richForms[1]))
                }
                text=view
                val dp=c.resources.displayMetrics.density
                val root=FrameLayout(activity).apply{setBackgroundColor(Color.BLACK)}
                root.addView(view,FrameLayout.LayoutParams((280*dp).toInt(),FrameLayout.LayoutParams.WRAP_CONTENT).apply{
                    leftMargin=(20*dp).toInt();topMargin=(160*dp).toInt()
                })
                activity.setContentView(root)
            }
            override fun onActivityCreated(activity:Activity,state:Bundle?){}
            override fun onActivityStarted(activity:Activity){}
            override fun onActivityResumed(activity:Activity){}
            override fun onActivityPaused(activity:Activity){}
            override fun onActivityStopped(activity:Activity){}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
            override fun onActivityDestroyed(activity:Activity){}
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val slotPositions=mutableListOf<List<Float>>()
                val selectionGeometry=org.json.JSONArray()
                for(phrase in forms) {
                i.runOnMainSync{
                    assertTrue(text!!.setAlignedForms(richForms[forms.indexOf(phrase)],richForms[forms.indexOfFirst{it!=phrase}]))
                    text!!.present(0,c.resources.displayMetrics.widthPixels.toFloat())
                    val native=text!!.text as android.text.Spanned
                    val space=phrase.indexOf("per i")+3
                    assertTrue("Code background must survive inside a styled whitespace separator",
                        native.getSpans(space,space+1,android.text.style.BackgroundColorSpan::class.java).isNotEmpty())
                    assertTrue("Code font must survive inside a styled whitespace separator",
                        native.getSpans(space,space+1,android.text.style.TypefaceSpan::class.java).isNotEmpty())
                }
                device.waitForIdle()
                i.runOnMainSync {
                    val view=text!!
                    var offset=0
                    slotPositions+=phrase.split(" ").map{word ->
                        val x=view.layout.getPrimaryHorizontal(offset)
                        offset+=word.length+1
                        x
                    }
                }
                var x=0f;var y=0f
                i.runOnMainSync {
                    val v=text!!;val location=IntArray(2);v.getLocationOnScreen(location)
                    x=location[0]+v.totalPaddingLeft+v.paint.measureText("Pe")/2
                    y=location[1]+v.totalPaddingTop+v.layout.getLineBaseline(0)+v.paint.fontMetrics.ascent/2
                }
                var down=android.os.SystemClock.uptimeMillis()
                fun inject(action:Int):Boolean {
                    val event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                    event.source=InputDevice.SOURCE_TOUCHSCREEN
                    return try{i.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}
                }
                fun recordInput(stage:String) {
                    i.runOnMainSync {
                        val view=text!!
                        inputTrace.put(org.json.JSONObject().put("stage",stage)
                            .put("focus",view.isFocused).put("selectable",view.isTextSelectable)
                            .put("longClickable",view.isLongClickable)
                            .put("movement",view.movementMethod?.javaClass?.name)
                            .put("start",view.selectionStart).put("end",view.selectionEnd)
                            .put("helps",helped.get()).put("longPresses",longPresses.get())
                            .put("x",x).put("y",y).put("held",interactionHold.get()))
                        java.io.File(c.cacheDir,"native-rich-input-trace.json").writeText(inputTrace.toString(2))
                    }
                }
                val beforeHelp=helped.get()
                assertTrue(inject(MotionEvent.ACTION_DOWN))
                i.runOnMainSync {
                    assertFalse("An ordinary pointer-down must not hold the shared wave",interactionHold.get())
                    val view=text!!
                    fun painted():android.graphics.Bitmap {
                        val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(image))
                        return image
                    }
                    val before=painted()
                    val semanticSnapshot=view.text.toString()
                    view.present(4200,c.resources.displayMetrics.widthPixels.toFloat())
                    val after=painted()
                    assertFalse("Native ink must keep advancing while a tap is pressed",before.sameAs(after))
                    assertEquals("Android pointer offsets must retain the down snapshot",semanticSnapshot,view.text.toString())
                    before.recycle();after.recycle()
                }
                assertTrue(inject(MotionEvent.ACTION_UP))
                assertFalse("A released tap must not leave a quiet-period hold",interactionHold.get())
                assertEquals("A glyph tap must dispatch help exactly once",beforeHelp+1,helped.get())
                assertEquals("Help must use the currently displayed form",phrase,lastHelp.get())
                recordInput("glyph-tap")
                i.runOnMainSync{text!!.present(0,c.resources.displayMetrics.widthPixels.toFloat())}
                device.waitForIdle()
                // These are deliberately separate single-tap and hold actions,
                // not an unintended double-tap followed by native text dragging.
                Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong()+50)
                // First target word is shorter than its support reservation.
                if(phrase==forms[0]) {
                    val originalX=x
                    i.runOnMainSync {
                        val view=text!!;val location=IntArray(2);view.getLocationOnScreen(location)
                        x=location[0]+view.totalPaddingLeft+view.layout.getPrimaryHorizontal(phrase.substringBefore(" ").length)-3
                    }
                    down=android.os.SystemClock.uptimeMillis()
                    assertTrue(inject(MotionEvent.ACTION_DOWN))
                    Thread.sleep(60)
                    assertTrue(inject(MotionEvent.ACTION_UP))
                    assertEquals("Reserved blank width must not dispatch help",beforeHelp+1,helped.get())
                    x=originalX
                    recordInput("reserved-blank-tap")
                    Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong()+50)
                }
                down=android.os.SystemClock.uptimeMillis()
                try {
                    assertTrue(inject(MotionEvent.ACTION_DOWN))
                    Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong()+350)
                    recordInput("held-before-release")
                } finally {assertTrue(inject(MotionEvent.ACTION_UP))}
                recordInput("hold-released")
                val copy=device.wait(Until.findObject(By.text("Copy")),5000)
                if(copy==null)device.takeScreenshot(java.io.File(c.cacheDir,"native-reserved-word-failure.png"))
                assertNotNull("Native reserved word did not offer Copy",copy)
                assertEquals("A long hold must select, never dispatch reading help",beforeHelp+1,helped.get())
                i.runOnMainSync {
                    val spans=(text!!.text as android.text.Spanned).getSpans(0,text!!.text.length,
                        android.text.style.ClickableSpan::class.java)
                    assertTrue("Inline help must have a platform accessibility span",spans.isNotEmpty())
                }
                var selected=""
                i.runOnMainSync{val v=text!!;selected=v.text.subSequence(v.selectionStart,v.selectionEnd).toString()}
                assertEquals("Native selection must select the actual word",phrase.substringBefore(" "),selected)
                i.runOnMainSync {
                    assertTrue("Native menu must hold the shared interaction clock",interactionHold.get())
                    val before=text!!.text.toString()
                    text!!.present(4200,c.resources.displayMetrics.widthPixels.toFloat())
                    assertEquals("A native menu must retain the precise selected language",before,text!!.text.toString())
                    assertTrue(text!!.setAlignedForms(phrase+"!",forms.first{it!=phrase}+"!"))
                    assertEquals("Incoming content must preserve the selected snapshot",before,text!!.text.toString())
                }
                // Record the settled native affordance, not the handle's
                // entrance animation inherited from the previous selection.
                Thread.sleep(500)
                i.runOnMainSync {
                    val view=text!!
                    val path=android.graphics.Path()
                    view.layout.getSelectionPath(view.selectionStart,view.selectionEnd,path)
                    val bounds=android.graphics.RectF()
                    path.computeBounds(bounds,true)
                    val line=view.layout.getLineForOffset(view.selectionStart)
                    assertTrue("A single selected native word must not highlight another line",
                        bounds.bottom<=view.layout.getLineBottom(line)+1f)
                    val endLine=view.layout.getLineForOffset(view.selectionEnd)
                    selectionGeometry.put(org.json.JSONObject()
                        .put("visible",view.text.toString()).put("layoutText",view.layout.text.toString())
                        .put("start",view.selectionStart).put("end",view.selectionEnd)
                        .put("startLine",line).put("endLine",endLine)
                        .put("endCaretX",view.layout.getPrimaryHorizontal(view.selectionEnd))
                        .put("endLineBaseline",view.layout.getLineBaseline(endLine))
                        .put("highlightBounds",bounds.toString()))
                    java.io.File(c.cacheDir,"native-selection-geometry.json").writeText(selectionGeometry.toString(2))
                }
                device.takeScreenshot(java.io.File(c.cacheDir,"native-reserved-word-selection.png"))
                val all=device.wait(Until.findObject(By.text("Select all")),3000)
                assertNotNull("Native paragraph did not offer Select all",all)
                all!!.click()
                i.runOnMainSync{val v=text!!;assertEquals(phrase,v.text.subSequence(v.selectionStart,v.selectionEnd).toString())}
                device.wait(Until.findObject(By.text("Copy")),3000)!!.click()
                var copied=""
                i.runOnMainSync{copied=(c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()}
                assertEquals("Native reserved span corrupted clipboard text",phrase,copied)
                i.runOnMainSync{assertEquals("Released selection must apply the latest deferred content",phrase+"!",text!!.text.toString())}
                }
                assertEquals("Paired grammar forms must preserve all six native slot positions",slotPositions[0],slotPositions[1])
                val observed=mutableSetOf<String>()
                i.runOnMainSync {
                    assertFalse("Copy must release the native interaction hold",interactionHold.get())
                    val view=text!!
                    assertTrue(view.setAlignedForms(richForms[0],richForms[1]))
                    for(time in 0L..8400L step 100L) {
                        view.present(time,c.resources.displayMetrics.widthPixels.toFloat())
                        observed+=view.text.toString()
                    }
                    assertFalse("Unaligned phrases must be rejected",view.setAlignedForms("one word","three different words"))
                }
                assertTrue("A complete shared-clock period must show the target form",forms[0] in observed)
                assertTrue("A complete shared-clock period must show the grammar-preserving support form",forms[1] in observed)
                // Genuine Android display-clock qualification, with no idle wait
                // while the loop runs and guaranteed owned callback teardown.
                val liveForms=mutableSetOf<String>()
                val liveClock=ImmersionReadingWaveClock()
                val liveOwner=Any()
                var frames=0
                var previous=0L
                var active=true
                val callback=object:android.view.Choreographer.FrameCallback {
                    override fun doFrame(now:Long) {
                        if(!active)return
                        if(previous!=0L)liveClock.advanceNanos(now-previous)
                        previous=now
                        text!!.present(liveClock.elapsedMs,c.resources.displayMetrics.widthPixels.toFloat())
                        liveForms+=text!!.text.toString()
                        frames++
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                try {
                    i.runOnMainSync {
                        text!!.onInteractionHold={held->interactionHold.set(held);liveClock.selecting(liveOwner,held)}
                        android.view.Choreographer.getInstance().postFrameCallback(callback)
                    }
                    Thread.sleep(11000)
                } finally {
                    i.runOnMainSync {
                        active=false
                        android.view.Choreographer.getInstance().removeFrameCallback(callback)
                    }
                }
                assertTrue("Native display clock did not produce sustained rendering",frames>30)
                assertTrue("Native live wave never presented the complete target",forms[0] in liveForms)
                assertTrue("Native live wave never presented the complete grammar-preserving support",forms[1] in liveForms)
            }
        } finally {app.unregisterActivityLifecycleCallbacks(lifecycle)}
    }
}
