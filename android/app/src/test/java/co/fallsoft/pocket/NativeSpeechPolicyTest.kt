package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class NativeSpeechPolicyTest {
    @Test fun encodedErrorsAndPartialChunksNeverBecomeResumableAudio(){
        assertFalse(validNativeSpeechAudio(ByteArray(128)))
        assertFalse(validNativeSpeechAudio("<html>provider refused this request and this is not audio</html>".toByteArray()))
        assertFalse(validNativeSpeechAudio(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte())))
    }
    @Test fun remoteRecordedAndBufferedAudioContainersAreAccepted(){
        val webm=ByteArray(128);byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()).copyInto(webm);assertTrue(validNativeSpeechAudio(webm))
        val ogg=ByteArray(128);"OggS".toByteArray().copyInto(ogg);assertTrue(validNativeSpeechAudio(ogg))
        val wav=ByteArray(128);"RIFF".toByteArray().copyInto(wav);"WAVE".toByteArray().copyInto(wav,8);assertTrue(validNativeSpeechAudio(wav))
        "HTML".toByteArray().copyInto(wav,8);assertFalse(validNativeSpeechAudio(wav))
    }
    @Test fun oversizedProviderOutputIsRejectedEvenWithRecognizedHeader(){
        val bytes=ByteArray(16*1024*1024+1);"OggS".toByteArray().copyInto(bytes);assertFalse(validNativeSpeechAudio(bytes))
    }
}
