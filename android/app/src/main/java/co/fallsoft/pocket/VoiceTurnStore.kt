package co.fallsoft.pocket

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Durable voice ownership. Never holds credentials, UI state, or a microphone. All calls are IO-only. */
internal class VoiceTurnStore(context: Context, name: String = "voice-turns") : AutoCloseable {
    data class Origin(val environment: String, val pairing: String, val endpoint: String, val destination: String) {
        init {
            require(environment.isNotBlank() && pairing.isNotBlank() && endpoint.isNotBlank())
            require(JSONObject(destination).length() > 0)
        }
    }
    enum class Capture { RECORDING, SEALING, SEALED, INTERRUPTED }
    enum class Delivery { UNSUBMITTED, SUBMITTING, STORED, ACCEPTED, UNKNOWN, COMPLETED, FAILED, CANCELLED }
    enum class Playback { UNAVAILABLE, READY, PLAYING, PAUSED, COMPLETED, FAILED }
    data class Turn(
        val key: String, val id: String, val origin: Origin, val capture: Capture,
        val delivery: Delivery, val playback: Playback, val sha256: String?,
        val bytes: Long, val receipt: String?, val problem: String?, val createdAt: Long,
        val revision: Long
    )
    private val root = File(context.noBackupFilesDir, name)
    private val helper: SQLiteOpenHelper
    init {
        ioOnly()
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        check(root.isDirectory || root.mkdirs())
        helper = object : SQLiteOpenHelper(context, File(root, "journal.db").absolutePath, null, 1) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("PRAGMA synchronous=FULL")
            }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""CREATE TABLE turns(
                    turn_key TEXT PRIMARY KEY, id TEXT NOT NULL, environment TEXT NOT NULL,
                    pairing TEXT NOT NULL, endpoint TEXT NOT NULL, destination TEXT NOT NULL,
                    capture TEXT NOT NULL, delivery TEXT NOT NULL, playback TEXT NOT NULL,
                    sha256 TEXT, bytes INTEGER NOT NULL DEFAULT 0, receipt TEXT, problem TEXT,
                    created_at INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(environment,pairing,id))""")
                db.execSQL("CREATE INDEX voice_delivery ON turns(delivery,created_at)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unsupported voice journal upgrade: $oldVersion to $newVersion")
            }
        }
        helper.writableDatabase
    }
    private fun ioOnly() { check(Looper.myLooper() != Looper.getMainLooper()) { "Voice persistence must run off the UI thread" } }
    private val db get(): SQLiteDatabase { ioOnly(); return helper.writableDatabase }
    private fun validAudio(wav: ByteArray): Boolean {
        if(wav.size !in 3244..3840044 || wav.size % 2 != 0)return false
        val b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun text(at:Int,length:Int)=wav.copyOfRange(at,at+length).toString(Charsets.US_ASCII)
        return text(0,4)=="RIFF" && text(8,8)=="WAVEfmt " && b.getInt(4)==wav.size-8 &&
            b.getInt(16)==16 && b.getShort(20).toInt()==1 && b.getShort(22).toInt()==1 &&
            b.getInt(24)==16000 && b.getInt(28)==32000 && b.getShort(32).toInt()==2 &&
            b.getShort(34).toInt()==16 && text(36,4)=="data" && b.getInt(40)==wav.size-44
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun key(origin: Origin, id: String) = hash(JSONArrayIdentity.encode(origin.environment, origin.pairing, id).toByteArray(Charsets.UTF_8))
    private fun syncDirectory() {
        val fd = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun row(cursor: Cursor): Turn {
        fun s(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun n(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return Turn(s("turn_key"), s("id"), Origin(s("environment"),s("pairing"),s("endpoint"),s("destination")),
            Capture.valueOf(s("capture")), Delivery.valueOf(s("delivery")), Playback.valueOf(s("playback")),
            s("sha256"), n("bytes"), s("receipt"), s("problem"), n("created_at"), n("revision"))
    }
    fun get(key: String): Turn? = db.query("turns", null, "turn_key=?", arrayOf(key),null,null,null).use { if(it.moveToFirst()) row(it) else null }
    fun list(): List<Turn> = db.query("turns",null,null,null,null,null,"created_at,turn_key").use { cursor -> buildList { while(cursor.moveToNext()) add(row(cursor)) } }
    fun begin(id: String, origin: Origin): Turn {
        require(id.matches(Regex("[a-zA-Z0-9_-]{8,100}")))
        val key = key(origin,id)
        get(key)?.let { check(it.origin == origin) { "Voice identity already belongs to a different destination" }; return it }
        val values = ContentValues().apply {
            put("turn_key",key);put("id",id);put("environment",origin.environment);put("pairing",origin.pairing)
            put("endpoint",origin.endpoint);put("destination",origin.destination)
            put("capture",Capture.RECORDING.name);put("delivery",Delivery.UNSUBMITTED.name)
            put("playback",Playback.UNAVAILABLE.name);put("created_at",System.currentTimeMillis())
        }
        db.insertWithOnConflict("turns",null,values,SQLiteDatabase.CONFLICT_IGNORE)
        return requireNotNull(get(key)).also { check(it.origin == origin) { "Voice identity belongs to a different destination" } }
    }
    /** Interrupted PCM stays private and inspectable; it is never eligible for automatic submission. */
    fun captureFile(key:String):File {
        check(requireNotNull(get(key)).capture==Capture.RECORDING)
        return File(root,"$key.capturing.pcm")
    }
    /** File is durable before the row can become eligible for delivery. Repeating identical sealing is safe. */
    fun seal(key: String, wav: ByteArray): Turn {
        require(validAudio(wav)) { "Expected complete mono 16 kHz PCM16 WAV" }
        val turn = requireNotNull(get(key)); val digest = hash(wav)
        if(turn.capture == Capture.SEALED) { check(turn.sha256 == digest && turn.bytes == wav.size.toLong());audio(turn);return turn }
        check(turn.capture == Capture.RECORDING && turn.delivery == Delivery.UNSUBMITTED)
        // Claim file publication before writing. Another writer cannot replace a sealed file
        // between its rename and the database transition.
        val claimed = db.compileStatement("UPDATE turns SET capture='SEALING',sha256=?,bytes=?,revision=revision+1 WHERE turn_key=? AND capture='RECORDING' AND delivery='UNSUBMITTED'").use {
            it.bindString(1,digest);it.bindLong(2,wav.size.toLong());it.bindString(3,key);it.executeUpdateDelete()
        }
        check(claimed == 1) { "Capture already sealing or interrupted" }
        val temp = File(root,"$key.preparing"); val target = File(root,"$key.wav")
        FileOutputStream(temp).use { it.write(wav);it.fd.sync() }
        check(temp.renameTo(target));syncDirectory()
        check(hash(target.readBytes()) == digest) { "Recording changed during publication" }
        val changed = db.compileStatement("UPDATE turns SET capture='SEALED',revision=revision+1 WHERE turn_key=? AND capture='SEALING' AND sha256=?").use {
            it.bindString(1,key);it.bindString(2,digest);it.executeUpdateDelete()
        }
        check(changed == 1) { "Capture changed before sealing" }
        File(root,"$key.capturing.pcm").delete()
        return requireNotNull(get(key))
    }
    fun audio(turn: Turn): File {
        ioOnly();check(turn.capture == Capture.SEALED)
        val file = File(root,"${turn.key}.wav")
        check(file.isFile && file.length() == turn.bytes && hash(file.readBytes()) == turn.sha256) { "Saved recording failed integrity verification" }
        return file
    }
    /** CAS prevents two schedulers from admitting the same local turn. Unknown is never eligible. */
    fun claim(key: String): Boolean = db.compileStatement("UPDATE turns SET delivery='SUBMITTING',revision=revision+1 WHERE turn_key=? AND capture='SEALED' AND delivery='UNSUBMITTED'").use {
        it.bindString(1,key);it.executeUpdateDelete() == 1
    }
    /** Only definite pre-admission failures may release a claim for another attempt. */
    fun notAdmitted(key: String, reason: String) = transition(key,setOf(Delivery.SUBMITTING),Delivery.UNSUBMITTED,null,reason)
    fun uncertain(key: String, reason: String) = transition(key,setOf(Delivery.SUBMITTING,Delivery.STORED,Delivery.ACCEPTED),Delivery.UNKNOWN,null,reason)
    fun stored(key: String, receipt: JSONObject) = transition(key,setOf(Delivery.SUBMITTING,Delivery.STORED),Delivery.STORED,receipt,null)
    fun accepted(key: String, receipt: JSONObject) = transition(key,setOf(Delivery.SUBMITTING,Delivery.STORED,Delivery.UNKNOWN),Delivery.ACCEPTED,receipt,null)
    fun completed(key: String, receipt: JSONObject) = transition(key,setOf(Delivery.SUBMITTING,Delivery.STORED,Delivery.ACCEPTED,Delivery.UNKNOWN),Delivery.COMPLETED,receipt,null)
    fun failed(key: String, receipt: JSONObject) = transition(key,setOf(Delivery.SUBMITTING,Delivery.STORED,Delivery.ACCEPTED,Delivery.UNKNOWN),Delivery.FAILED,receipt,receipt.optString("error"))
    fun cancelUnsubmitted(key: String) = transition(key,setOf(Delivery.UNSUBMITTED),Delivery.CANCELLED,null,null)
    private fun transition(key: String, from: Set<Delivery>, to: Delivery, receipt: JSONObject?, problem: String?): Turn {
        val database = db;database.beginTransaction()
        try {
            val old = requireNotNull(get(key))
            if(receipt != null) check(receipt.optString("id") == old.id) { "Receipt belongs to another turn" }
            if(old.delivery != to || receipt != null && receipt.toString()!=old.receipt || old.problem!=problem) {
                check(old.delivery == to || old.delivery in from) { "Invalid voice delivery transition ${old.delivery} to $to" }
                val values = ContentValues().apply {
                    put("delivery",to.name);put("revision",old.revision+1)
                    if(receipt != null)put("receipt",receipt.toString())
                    if(problem != null)put("problem",problem)else putNull("problem")
                }
                check(database.update("turns",values,"turn_key=? AND revision=?",arrayOf(key,old.revision.toString())) == 1)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return requireNotNull(get(key))
    }
    /** Playback can fail without altering a delivery receipt or making a turn eligible to resend. */
    fun playback(key: String, phase: Playback): Turn {
        val old = requireNotNull(get(key));check(old.delivery == Delivery.COMPLETED)
        db.execSQL("UPDATE turns SET playback=?,revision=revision+1 WHERE turn_key=?",arrayOf(phase.name,key))
        return requireNotNull(get(key))
    }
    /** Called once by the sole dispatcher when acquiring ownership after process startup. */
    fun recoverInterruptedOwner() {
        val database = db
        database.beginTransaction()
        try {
            // Recover either side of the file rename using the committed sealing digest.
            list().filter { it.capture == Capture.SEALING }.forEach { turn ->
                val final = File(root,"${turn.key}.wav")
                val temp = File(root,"${turn.key}.preparing")
                val candidate = if(final.isFile)final else temp
                if(candidate.isFile && candidate.length() == turn.bytes && hash(candidate.readBytes()) == turn.sha256) {
                    if(candidate != final) { check(temp.renameTo(final));syncDirectory() }
                    database.execSQL("UPDATE turns SET capture='SEALED',revision=revision+1 WHERE turn_key=?",arrayOf(turn.key))
                }
            }
            database.execSQL("UPDATE turns SET capture='INTERRUPTED',problem='Capture interrupted before sealing',revision=revision+1 WHERE capture IN ('RECORDING','SEALING')")
            database.execSQL("UPDATE turns SET delivery='UNKNOWN',problem='Submission interrupted; inspect original receipt',revision=revision+1 WHERE delivery='SUBMITTING'")
            database.execSQL("UPDATE turns SET playback='PAUSED',revision=revision+1 WHERE playback='PLAYING'")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    /** Failed capture is never uploadable; retain its private PCM for inspection. */
    fun interruptCapture(key: String, message: String) {
        db.execSQL("UPDATE turns SET capture='INTERRUPTED',problem=?,revision=revision+1 WHERE turn_key=? AND capture='RECORDING' AND delivery='UNSUBMITTED'",arrayOf(message,key))
    }
    fun problem(key: String, message: String) {
        db.execSQL("UPDATE turns SET problem=?,revision=revision+1 WHERE turn_key=?",arrayOf(message,key))
    }
    override fun close() { ioOnly();helper.close() }
}

private object JSONArrayIdentity {
    fun encode(vararg values: String): String = org.json.JSONArray(values.toList()).toString()
}
