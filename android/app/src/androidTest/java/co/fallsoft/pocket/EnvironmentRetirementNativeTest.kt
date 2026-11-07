package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnvironmentRetirementNativeTest {
    @Test fun retirementDeletesOriginalUnknownCopiesAndAudioButKeepsOtherHost() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="retirement-qa-"+UUID.randomUUID()
        val prefs=context.getSharedPreferences(name,0)
        try {
            prefs.edit().putString("server","https://current.example").putString("token","current-token").commit()
            val profiles=EnvironmentProfiles(prefs)
            val old=profiles.add("Retired","https://old.example","old-token","old-device")
            val mac=profiles.add("Mac","https://mac.example","mac-token","mac-device")
            val original=JSONArray().put(JSONObject().put("environment",old.id).put("state","unknown")).put(JSONObject().put("environment",mac.id).put("state","unknown"))
            prefs.edit().putString(old.key("draft:same"),"Discard").putString(mac.key("draft:same"),"Keep").putString("client-replies:v1",original.toString()).commit()
            val origin=VoiceTurnStore.Origin(old.id,"old-pairing","https://old.example",JSONObject().put("threadId","same").toString())
            val wav=VoiceRecording.wav(ByteArray(6400))
            VoiceTurnStore(context,name).use { store ->
                val oldTurn=store.seal(store.begin("unknown-recording",origin).key,wav)
                assertTrue(store.claim(oldTurn.key));store.recoverInterruptedOwner()
                assertEquals(VoiceTurnStore.Delivery.UNKNOWN,store.get(oldTurn.key)!!.delivery)
                val other=store.seal(store.begin("unknown-recording",origin.copy(environment=mac.id,endpoint="https://mac.example")).key,wav)
                val descriptor=FleetDescriptor.read(JSONObject().put("version",1).put("id","00000000-0000-4000-8000-000000000001").put("accountId","00000000-0000-4000-8000-000000000002").put("nodeId","00000000-0000-4000-8000-000000000003").put("sessionPath","/api/fleet/session").put("retiredOrigins",JSONArray().put("https://old.example")).put("members",JSONArray().put(JSONObject().put("id","00000000-0000-4000-8000-000000000003").put("name","Current").put("origin","https://current.example"))))
                assertTrue(profiles.reconcileFleet(descriptor))
                store.removeEnvironment(old.id)
                assertNull(store.get(oldTurn.key));assertFalse(File(context.noBackupFilesDir,"$name/${oldTurn.key}.wav").exists())
                assertArrayEquals(wav,store.audio(store.get(other.key)!!).readBytes())
                assertNull(profiles.capture(old.id));assertFalse(prefs.contains(old.key("token")))
                assertFalse(prefs.contains(old.key("draft:same")));assertEquals("Keep",prefs.getString(mac.key("draft:same"),null))
                assertEquals(mac.id,JSONArray(prefs.getString("client-replies:v1","[]")).getJSONObject(0).getString("environment"))
                assertFalse(profiles.reconcileFleet(descriptor))
            }
            VoiceTurnStore(context,name).use{assertEquals(listOf(mac.id),it.list().map{row->row.origin.environment})}
        } finally {context.deleteSharedPreferences(name);File(context.noBackupFilesDir,name).deleteRecursively()}
    }
}
