package co.fallsoft.pocket

/** Captions track actual synthesized chunks; no guessed word timestamps. */
data class SpeechCaptionState(val id:Long=0,val title:String="",val text:String="",val part:Int=0,val parts:Int=0,val phase:String="",val dismissed:Boolean=false){
    val visible get()=id!=0L&&text.isNotBlank()&&!dismissed
    val label get()=listOf(phase,if(parts>1)"Part ${part+1} of $parts" else "").filter{it.isNotBlank()}.joinToString(" · ")
    fun update(id:Long,title:String,text:String,part:Int,parts:Int)=SpeechCaptionState(id,title,text,part,parts,"Speaking",dismissed&&this.id==id)
    fun complete()=copy(phase="Spoken")
    fun pause()=copy(phase="Paused")
    fun dismiss()=copy(dismissed=true)
}
