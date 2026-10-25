package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity/Profile/HollywoodScreen HTTP/WebSocket fixture journey.
 * Seeded unknown receipts are NEVER submitted. Fresh owned nextcomp_env_qa only.
 * Routing/rendering proof is not provider, phone, push or voice qualification.
 */
@RunWith(AndroidJUnit4::class)
class HollywoodMixedCatalogNativeTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private val device get()=UiDevice.getInstance(i)
    private val roomId="cccccccc-cccc-4ccc-8ccc-ccccccccccc1"
    private val agent="dddddddd-dddd-4ddd-8ddd-ddddddddddd1"
    private val authority="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val base="http://127.0.0.1:18880"
    private val roomText="Canonical room answer from original authority"
    private val sessionText="Stock session answer from original stock host"
    private val roomDraft="Room draft edited in mounted conversation"
    private val stockDraft="Session draft edited in mounted conversation"
    private fun main(b:()->Unit)=i.runOnMainSync(b)
    private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
    private fun await(label:String,b:()->Boolean){
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end){
            if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache()
            if(b())return
            Thread.sleep(80)
        }
        capture("failed");assertTrue(label,b())
    }
    private fun capture(name:String){
        device.dumpWindowHierarchy(java.io.File(context.cacheDir,"mixed-"+name+".xml"))
        // UiDevice.takeScreenshot hangs on the installed API33 image; the
        // supported shell screencap path is finite and independently exercised.
        val path="/data/local/tmp/mixed-"+name+".png"
        device.executeShellCommand("screencap -p "+path)
        assertTrue(device.executeShellCommand("stat -c %s "+path).trim().toLong()>1000)
    }
    private fun click(selector:BySelector){
        await("Missing clickable "+selector){device.hasObject(selector)}
        requireNotNull(device.findObject(selector)).click()
    }
    private fun binding()=JSONObject().put("id","lax").put("api",3).put("configured",true).put("available",true)
        .put("bindingId","e".repeat(64)).put("installationId",authority).put("authorityId",authority)
        .put("principalId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").put("defaultRoomId",roomId).put("voice",false)

    @Test fun hollywoodEntryRetainsMixedSourcesAndOriginalActionsThroughAnOutage(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        val roomOwner=backendOwner(base,"synthetic-mixed-room")
        val stockOwner=backendOwner(base,"synthetic-mixed-stock")
        val stockKey="environment:stock-host:draft:existing-session"
        val stockIntent=ClientReplyIntent("00000000-0000-4000-8000-000000000119","stock-host",stockOwner,
            "existing-session","Saved unknown stock input","auto",stockKey,phase="observed",state="unknown")
        // Only this fresh disposable app is initialized, never a historical pairing.
        check(prefs.edit().clear()
            .putString("environments:v1",JSONArray().put(JSONObject().put("id","room-host").put("name","Room host"))
                .put(JSONObject().put("id","stock-host").put("name","Stock host")).toString())
            .putString("activeEnvironment","room-host")
            .putString("environment:room-host:server",base).putString("environment:room-host:token","synthetic-mixed-room")
            .putString("environment:stock-host:server",base).putString("environment:stock-host:token","synthetic-mixed-stock")
            .putString("backend:"+roomOwner,"lax").putString("backend:"+stockOwner,"codex")
            .putString(stockKey,"Initial stock draft")
            .putString("client-replies:v1",JSONArray().put(stockIntent.json()).toString()).commit())
        main{Pocket.init(context)}
        val client=HollywoodClient(roomOwner,binding(),base,"synthetic-mixed-room")
        val scope=HollywoodScope(roomId,agent,"room",null,"request")
        client.saveDraft(scope,"Initial room draft")
        val roomIntent=client.capture(scope,"Saved unknown room input").put("state","unknown").toString()
        val roomPendingKey="pending:"+client.key(scope)
        check(prefs.edit().putString(roomPendingKey,roomIntent).commit())
        fun preserved(){
            assertEquals(roomDraft,client.draft(scope));assertEquals(stockDraft,prefs.getString(stockKey,null))
            assertEquals("Changed unknown room receipt",roomIntent,prefs.getString(roomPendingKey,null))
            val saved=ClientReplyJournal({prefs.getString("client-replies:v1","[]")!!},{false}).all().single()
            assertEquals(stockIntent.id,saved.id);assertEquals(stockOwner,saved.owner)
            assertEquals("stock-host",saved.environment);assertEquals("unknown",saved.state)
        }
        // Match the existing repository MainActivity probes; no idle-dependent
        // ActivityScenario cleanup can conceal a routing assertion failure.
        main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        try {
            await("Original room text/editor not mounted"){device.hasObject(By.text(roomText))&&device.hasObject(By.clazz("android.widget.EditText"))}
            assertEquals("room-host",Pocket.environmentId);assertTrue(BackendNavigation.nativeSelected())
            assertEquals("Initial room draft",device.findObject(By.clazz("android.widget.EditText")).text)
            device.findObject(By.clazz("android.widget.EditText")).text=roomDraft
            await("Room typing not persisted"){client.draft(scope)==roomDraft};capture("room")
            click(By.res("native-room-controls"));click(By.res("native-room-catalog"))
            await("Hollywood list missing mixed sources"){device.hasObject(By.desc("Open Chat for Original room"))&&device.hasObject(By.desc("Open Chat for Original session"))}
            val entries=UnifiedWorkCatalog.snapshots.flatMap{it.entries}
            val room=entries.single{it.key.kind==WorkKind.ROOM&&it.key.id==roomId}
            val stock=entries.single{it.key.kind==WorkKind.SESSION&&it.key.id=="existing-session"}
            assertEquals("room-host",room.key.source.environment);assertEquals(authority,room.key.authority)
            assertEquals("stock-host",stock.key.source.environment);assertEquals("",stock.key.authority)
            assertTrue(room.key.source.owner.startsWith(roomOwner));assertEquals(stockOwner,stock.key.source.owner)
            capture("mixed-list");click(By.desc("Open Keyboard for Original session"))
            await("Original stock text/editor not mounted"){Pocket.environmentId=="stock-host"&&Pocket.selected=="existing-session"&&device.hasObject(By.text(sessionText))&&device.hasObject(By.clazz("android.widget.EditText"))}
            assertFalse(BackendNavigation.nativeSelected())
            assertEquals("Initial stock draft",device.findObject(By.clazz("android.widget.EditText")).text)
            device.findObject(By.clazz("android.widget.EditText")).text=stockDraft
            await("Stock typing not persisted"){prefs.getString(stockKey,null)==stockDraft}
            preserved();capture("session");click(By.text("Stock host"));click(By.text("Back"))
            await("Stock shared list lost room"){Pocket.selected==null&&device.hasObject(By.desc("Open Chat for Original room"))}
            click(By.desc("Open Keyboard for Original room"))
            await("Original room/draft not mounted on return"){Pocket.environmentId=="room-host"&&device.hasObject(By.text(roomText))&&device.findObject(By.clazz("android.widget.EditText"))?.text==roomDraft}
            preserved();capture("returned-room");click(By.res("native-room-controls"));click(By.res("native-room-catalog"))
            await("Room list missing"){device.hasObject(By.desc("Open Chat for Original session"))}
            qa("room-outage");main{UnifiedWorkCatalog.refresh()}
            await("Unavailable source not isolated"){
                val snapshots=UnifiedWorkCatalog.snapshots
                snapshots.any{it.key.environment=="room-host"&&!it.available&&it.entries.isNotEmpty()}&&
                    snapshots.any{it.key.environment=="stock-host"&&it.available&&it.entries.isNotEmpty()}&&
                    device.hasObject(By.textContains("Synthetic room catalog unavailable"))
            }
            assertTrue(device.hasObject(By.desc("Open Chat for Original room")));capture("isolated-outage")
            click(By.desc("Open Keyboard for Original session"))
            await("Room outage blocked stock"){Pocket.environmentId=="stock-host"&&device.hasObject(By.text(sessionText))}
            preserved();click(By.text("Stock host"));click(By.text("Back"))
            await("Shared list missing after outage"){Pocket.selected==null&&device.hasObject(By.desc("Open Chat for Original session"))}
            // A stale card must refuse changed pairing, not borrow selected credentials.
            check(prefs.edit().putString("environment:stock-host:token","synthetic-changed").commit())
            click(By.desc("Open Chat for Original session"))
            await("Stale action not refused"){Pocket.error.contains("pairing changed")}
            assertNull("Stale card retargeted conversation",Pocket.selected)
            check(prefs.edit().putString("environment:stock-host:token","synthetic-mixed-stock").commit())
            qa("room-online");main{UnifiedWorkCatalog.refresh()}
            await("Room source not recovered"){UnifiedWorkCatalog.snapshots.any{it.key.environment=="room-host"&&it.available}}
            click(By.desc("Open Keyboard for Original room"))
            await("Original room not mounted after recovery"){Pocket.environmentId=="room-host"&&device.hasObject(By.text(roomText))}
            preserved();capture("final-room")
            val state=qa("state")
            assertEquals("Navigation admitted task/provider write",0,state.getInt("blockedTaskWrites"))
            assertEquals("Wrong source route accepted",0,state.getInt("wrongSource"))
            assertTrue("Room WebSocket missing",state.getJSONArray("websockets").objects().any{it.s("source")=="room"})
            assertTrue("Stock WebSocket missing",state.getJSONArray("websockets").objects().any{it.s("source")=="stock"})
            assertTrue(state.getJSONArray("reads").objects().any{it.s("source")=="room"&&it.s("path").contains("/"+roomId+"/history")})
            assertTrue(state.getJSONArray("reads").objects().any{it.s("source")=="stock"&&it.s("path").contains("/existing-session")})
        }finally{main{PocketLive.stop()}}
    }
}
