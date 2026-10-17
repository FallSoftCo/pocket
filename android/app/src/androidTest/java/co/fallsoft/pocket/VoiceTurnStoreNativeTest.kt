package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual Android SQLite/file reopen boundaries; no HTTP fixtures or task submissions. */
@RunWith(AndroidJUnit4::class)
class VoiceTurnStoreNativeTest {
    @Test fun independentCaptureDeliveryAndPlaybackSurviveReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "nextcomp_env_qa")
        val context = instrumentation.targetContext
        val name = "voice-journal-qa-" + UUID.randomUUID()
        val origin = VoiceTurnStore.Origin("qa-environment-a","qa-pairing","http://127.0.0.1:18889",
            JSONObject().put("kind","room").put("roomId","qa-room-a").toString())
        val wav = VoiceRecording.wav(ByteArray(6400) { (it % 17).toByte() })
        var store = VoiceTurnStore(context,name)
        try {
            val first = store.seal(store.begin("original-turn-0001",origin).key,wav)
            assertTrue(store.claim(first.key))
            assertFalse(store.claim(first.key))
            // A second recording has independent storage even while the first send has no ACK.
            val second = store.seal(store.begin("another-turn-0002",origin.copy(
                destination=JSONObject().put("kind","room").put("roomId","qa-room-b").toString())).key,wav)
            store.close();store = VoiceTurnStore(context,name);store.recoverInterruptedOwner()
            assertEquals(VoiceTurnStore.Delivery.UNKNOWN,store.get(first.key)!!.delivery)
            assertFalse(store.claim(first.key))
            assertEquals(VoiceTurnStore.Delivery.UNSUBMITTED,store.get(second.key)!!.delivery)
            assertEquals(origin,store.get(first.key)!!.origin)
            assertArrayEquals(wav,store.audio(store.get(first.key)!!).readBytes())
            val receipt = JSONObject().put("id",first.id).put("state","completed").put("response","Original result")
            store.completed(first.key,receipt)
            store.playback(first.key,VoiceTurnStore.Playback.FAILED)
            assertEquals(VoiceTurnStore.Delivery.COMPLETED,store.get(first.key)!!.delivery)
            assertEquals(receipt.toString(),store.get(first.key)!!.receipt)
            assertFalse(store.claim(first.key))
            assertTrue(store.claim(second.key))
            store.close();store = VoiceTurnStore(context,name);store.recoverInterruptedOwner()
            assertEquals(VoiceTurnStore.Delivery.COMPLETED,store.get(first.key)!!.delivery)
            assertEquals(VoiceTurnStore.Playback.FAILED,store.get(first.key)!!.playback)
            assertEquals(VoiceTurnStore.Delivery.UNKNOWN,store.get(second.key)!!.delivery)
            // The same external ID in another environment is distinct, not a cross-host collision.
            val elsewhere=store.begin(first.id,origin.copy(environment="qa-environment-b"))
            assertNotEquals(first.key,elsewhere.key)
            assertEquals(VoiceTurnStore.Capture.RECORDING,elsewhere.capture)
            var conflict = false
            try { store.begin(first.id,origin.copy(destination=second.origin.destination)) } catch(_:IllegalStateException) { conflict = true }
            assertTrue("Immutable destination must not be overwritten",conflict)
            var wrongReceipt = false
            try { store.accepted(second.key,JSONObject().put("id","wrong-turn-id")) } catch(_:IllegalStateException) { wrongReceipt=true }
            assertTrue("Receipt identity must be checked",wrongReceipt)
            // Kill-point equivalent: durable seal intent/file exists, but final row update never ran.
            val third=store.seal(store.begin("seal-boundary-0003",origin).key,wav)
            store.close()
            val folder=File(context.noBackupFilesDir,name)
            android.database.sqlite.SQLiteDatabase.openDatabase(File(folder,"journal.db").absolutePath,null,0).use { db ->
                db.execSQL("UPDATE turns SET capture='SEALING' WHERE turn_key=?",arrayOf(third.key))
            }
            check(File(folder,"${third.key}.wav").renameTo(File(folder,"${third.key}.preparing")))
            store=VoiceTurnStore(context,name);store.recoverInterruptedOwner()
            assertEquals(VoiceTurnStore.Capture.SEALED,store.get(third.key)!!.capture)
            assertArrayEquals(wav,store.audio(store.get(third.key)!!).readBytes())
            assertEquals(VoiceTurnStore.Capture.INTERRUPTED,store.get(elsewhere.key)!!.capture)
        } finally {
            store.close()
            // Only this test's random private journal, never app state or accepted deliveries.
            File(context.noBackupFilesDir,name).deleteRecursively()
        }
    }
}
