package co.fallsoft.pocket

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the private room feed from a completed real native LAX integration. */
class RoomActivityNativeFeedTest {
    @Test fun realNativeCommandsCoalesceAndExpandWithTheirActualOutputAndAttribution() {
        val path=System.getenv("HOLLYWOOD_NATIVE_HISTORY")
        assumeTrue("Explicit native qualification feed required",!path.isNullOrBlank())
        val events=JSONObject(File(path!!).readText()).getJSONArray("events").objects()
        val canonical=events.map{it.toString()}
        val commandIds=events.filter{it.optJSONObject("data")?.s("nativeType")=="commandExecution"}
            .map{it.getJSONObject("data").s("itemId")}.toSet()
        assertTrue(commandIds.isNotEmpty())
        val rows=roomTranscriptRows(events,"qualified-authority")
        val commands=rows.filter{it.s("itemId") in commandIds}
        assertEquals(commandIds.size,commands.size)
        assertTrue(commands.all{it.s("status")=="completed"&&it.optInt("exitCode")==0})
        assertTrue(commands.any{it.s("detail").contains("HOLLYWOOD_ROOM_VERIFIED")})
        assertTrue(commands.all{it.s("detail").contains("Agent:")&&it.s("detail").contains("Host:")&&it.s("nativeTurn").isNotBlank()})
        val collapsed=commandRunProjection(rows,emptySet())
        assertFalse(collapsed.rows.any{it.s("itemId") in commandIds&&it.s("kind")=="activity"})
        val expanded=commandRunProjection(rows,commands.map{it.s("id")}.toSet())
        assertEquals(commands.map{it.s("id")}.toSet(),expanded.rows.filter{it.s("itemId") in commandIds&&it.s("kind")=="activity"}.map{it.s("id")}.toSet())
        val questionIds=events.filter{it.s("kind")=="question"}.map{it.s("id")}.toSet()
        assertTrue(questionIds.isNotEmpty())
        assertTrue(collapsed.rows.any{it.s("roomEventId") in questionIds&&it.s("kind")=="message"})
        assertEquals(rows.size,rows.map{it.s("id")}.toSet().size)
        assertFalse(rows.any{it.s("id").endsWith(":0")})
        assertEquals(canonical,events.map{it.toString()})
    }

    @Test fun identicalItemIdsInDifferentExecutionsNeverMergeAndUnknownRemainsVisible() {
        fun event(id:String,delivery:String,status:String)=JSONObject().put("id",id).put("authority","authority")
            .put("author","agent").put("kind","progress").put("body","Command")
            .put("data",JSONObject().put("type","executionActivity").put("nativeType","commandExecution")
                .put("itemId","same-item").put("deliveryId",delivery).put("generation",1)
                .put("thread","thread").put("turn","turn").put("status",status).put("hostId","host"))
        val source=listOf(event("start","first","inProgress"),event("other","second","unknown"),event("done","first","failed"))
        val rows=roomTranscriptRows(source,"scope")
        assertEquals(2,rows.size)
        assertEquals(listOf("failed","unknown"),rows.map{it.s("status")})
        assertEquals(2,commandRunProjection(rows,emptySet()).groups.size)
        val cold=roomTranscriptRows(listOf(source.last()),"scope").single()
        assertEquals(cold.s("id"),rows.first().s("id"))
    }
}
