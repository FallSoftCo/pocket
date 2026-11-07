package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real synthetic server receipt; delivery ownership is intentionally absent. */
class HollywoodConversationAttentionTest {
    private fun receipt():JSONObject = JSONObject(javaClass.getResource("/conversation-attention.json")!!.readText())
    @Test fun conversationalQuestionCanBeAnsweredWithoutAClaim() {
        val value=receipt()
        assertTrue(value.isNull("deliveryState"))
        assertTrue(hollywoodAttentionCanRespond(value))
        assertEquals(1,hollywoodRoomWork(JSONObject(),"unavailable",listOf(value)).questions.size)
        value.put("canRespond",false)
        assertFalse(hollywoodAttentionCanRespond(value))
        assertTrue(hollywoodRoomWork(JSONObject(),"unavailable",listOf(value)).questions.isEmpty())
    }
    @Test fun changedCallbackOrClosedQuestionCannotCaptureAnAnswer() {
        for(key in listOf("nativeThread","nativeTurn","requestDigest","sourceEventId")) {
            val value=receipt();value.getJSONObject("scope").put(key,"changed")
            assertFalse(key,hollywoodAttentionCanRespond(value))
        }
        val closed=receipt();closed.getJSONObject("attention").put("state","closed")
        assertFalse(hollywoodAttentionCanRespond(closed))
    }
}
