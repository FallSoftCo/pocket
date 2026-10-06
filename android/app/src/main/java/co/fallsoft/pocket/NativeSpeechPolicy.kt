package co.fallsoft.pocket

/** Reject error/HTML/random payloads before they become resumable private audio files. */
internal fun validNativeSpeechAudio(bytes:ByteArray):Boolean {
    if(bytes.size !in 45..16*1024*1024)return false
    val webm=bytes.take(4).map{it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3)
    val ogg=String(bytes,0,4,Charsets.US_ASCII)=="OggS"
    val wav=String(bytes,0,4,Charsets.US_ASCII)=="RIFF"&&String(bytes,8,4,Charsets.US_ASCII)=="WAVE"
    return webm||ogg||wav
}
