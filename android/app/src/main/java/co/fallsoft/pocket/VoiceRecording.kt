package co.fallsoft.pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object VoiceRecording {
    const val MAX_BYTES=3840000
    fun hasSpeech(pcm:ByteArray):Boolean {
        if(pcm.size<3200||pcm.size%2!=0)return false
        var energy=0L;var sum=0L;var peak=0
        for(i in pcm.indices step 2){val value=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt();peak=maxOf(peak,abs(value));sum+=value;energy+=value.toLong()*value}
        // Reject actual silence without trying to classify spoken commands on-device.
        val samples=pcm.size/2;val mean=sum/samples
        return peak>150&&energy/samples-mean*mean>625
    }
    fun wav(pcm:ByteArray):ByteArray {
        require(pcm.size<=MAX_BYTES&&pcm.size%2==0)
        val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(pcm.size+36);h.put("WAVEfmt ".toByteArray());h.putInt(16);h.putShort(1);h.putShort(1);h.putInt(16000);h.putInt(32000);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(pcm.size)
        return h.array()+pcm
    }
}
