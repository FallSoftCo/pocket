package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun completeSummaryBeyondOldWordAndCharacterLimitsIsPreserved(){
        val text="The fix is in place and the tests all pass, so you can now open the app and read the full update when you have a moment, with no need to change any notification or connection settings on your phone."
        assertTrue(text.split(" ").size>28);assertTrue(text.length>180)
        assertEquals(text,SpeechText.excerpt(text))
    }
    @Test fun longFallbackStopsAtACompleteSentence(){
        val source="The update is installed. "+"We still need to check the rest of this very long detail ".repeat(15)+"."
        assertEquals("The update is installed. Open Pocket for the full update.",SpeechText.excerpt(source))
        assertEquals("Open Pocket for the full update.",SpeechText.excerpt("word ".repeat(200)))
    }
    @Test fun transportBudgetDoesNotSplitUnicodeSpeech(){
        val result=SpeechText.excerpt("完了しました。 "+"詳しい報告内容を確認してください".repeat(30)+"。")
        assertTrue(result.toByteArray(Charsets.UTF_8).size<=600);assertTrue(result.startsWith("完了しました。"));assertTrue(result.endsWith("full update."))
    }
    @Test fun arbitrarilyLongSpeechIsChunkedWithoutLosingTheEnding(){
        val text=("This is a complete sentence that should be spoken in full. ").repeat(140)+"These are the final words."
        val chunks=SpeechText.chunks(text)
        assertTrue(chunks.size>10);assertTrue(chunks.all{it.length<=600})
        assertEquals(text,chunks.joinToString(""));assertTrue(chunks.last().endsWith("These are the final words."))
        val unicode="🌿".repeat(1001)
        val parts=SpeechText.chunks(unicode,599)
        assertEquals(unicode,parts.joinToString(""));assertTrue(parts.none{Character.isHighSurrogate(it.last())})
    }
    @Test fun cleanupKeepsCodeAndSecretsOutOfSpeech(){
        val result=SpeechText.excerpt("```sh\nprivate code\n```\n**Tests passed.** See [report](https://example.org/results). token=hidden /very/long/private/path")
        assertTrue(result.contains("Tests passed."));assertFalse(result.contains("hidden"));assertFalse(result.contains("https:"));assertFalse(result.contains("private/path"));assertFalse(result.contains("private code"))
    }
}
