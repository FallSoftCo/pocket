package co.fallsoft.pocket

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
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
        fun reservedPhrase(view:TextView, phrase:String):SpannableString {
            val value=SpannableString(phrase)
            val paired=forms.map{it.split(" ")}
            var offset=0
            phrase.split(" ").forEachIndexed { index,word ->
                val reserved=kotlin.math.ceil(paired.maxOf{view.paint.measureText(it[index])}).toInt()
                value.setSpan(object:ReplacementSpan(){
                    override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
                        fm?.let{paint.getFontMetricsInt(it)}
                        return reserved
                    }
                    override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
                        canvas.drawText(text,start,end,x,y.toFloat(),paint)
                    }
                },offset,offset+word.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offset+=word.length+1
            }
            return value
        }
        val app=c.applicationContext as Application
        var text:TextView?=null
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity:Activity,state:Bundle?) {
                if(activity !is MainActivity)return
                val view=TextView(activity).apply {
                    textSize=17f
                    setTextColor(Color.WHITE)
                    typeface=ResourcesCompat.getFont(c,R.font.inter_regular)
                    setTextIsSelectable(true)
                    setText(reservedPhrase(this,forms.first()),TextView.BufferType.SPANNABLE)
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
                for(phrase in forms) {
                i.runOnMainSync{text!!.setText(reservedPhrase(text!!,phrase),TextView.BufferType.SPANNABLE)}
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
                val down=android.os.SystemClock.uptimeMillis()
                fun inject(action:Int):Boolean {
                    val event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                    event.source=InputDevice.SOURCE_TOUCHSCREEN
                    return try{i.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}
                }
                try {
                    assertTrue(inject(MotionEvent.ACTION_DOWN))
                    Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong()+350)
                } finally {assertTrue(inject(MotionEvent.ACTION_UP))}
                val copy=device.wait(Until.findObject(By.text("Copy")),5000)
                if(copy==null)device.takeScreenshot(java.io.File(c.cacheDir,"native-reserved-word-failure.png"))
                assertNotNull("Native reserved word did not offer Copy",copy)
                var selected=""
                i.runOnMainSync{val v=text!!;selected=v.text.subSequence(v.selectionStart,v.selectionEnd).toString()}
                assertEquals("Native selection must select the actual word",phrase.substringBefore(" "),selected)
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
                }
                assertEquals("Paired grammar forms must preserve all six native slot positions",slotPositions[0],slotPositions[1])
            }
        } finally {app.unregisterActivityLifecycleCallbacks(lifecycle)}
    }
}
