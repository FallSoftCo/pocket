package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class VoiceRecordingTest {
    @Test fun silenceAndTinyNoiseNeverBecomeCommands(){assertFalse(VoiceRecording.hasSpeech(ByteArray(32000)));assertFalse(VoiceRecording.hasSpeech(ByteArray(32000){1}));assertFalse(VoiceRecording.hasSpeech(byteArrayOf(0,127)))}
    @Test fun quietSpeechIsKeptAndWavMatchesServerContract(){val pcm=ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN);repeat(16000){pcm.putShort(if(it%10<5)400 else -400)};assertTrue(VoiceRecording.hasSpeech(pcm.array()));val wav=VoiceRecording.wav(pcm.array());val h=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);assertEquals(32044,wav.size);assertEquals(32036,h.getInt(4));assertEquals(16000,h.getInt(24));assertEquals(32000,h.getInt(40));assertArrayEquals(pcm.array(),wav.copyOfRange(44,wav.size))}
    @Test(expected=IllegalArgumentException::class) fun partialPcmSampleIsRejected(){VoiceRecording.wav(byteArrayOf(1))}
}
