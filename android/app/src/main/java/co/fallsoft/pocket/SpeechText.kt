package co.fallsoft.pocket

import java.text.BreakIterator
import java.util.Locale

/** A bounded excerpt ends at a sentence boundary; never chop words for speech. */
object SpeechText {
    private const val more="Open Pocodex for the full update."
    fun clean(source:String):String = source.replace(Regex("```[\\s\\S]*?(?:```|$)")," ")
        .replace(Regex("`[^`]*`")," ").replace(Regex("\\[([^]]+)]\\([^)]*\\)"),"$1")
        .replace(Regex("https?://\\S+|(?:^|\\s)(?:/?[\\w.-]+/){2,}\\S*")," ")
        .replace(Regex("\\b(?:Bearer\\s+\\S+|(?:api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+)",RegexOption.IGNORE_CASE),"private value")
        .replace(Regex("^[\\s]*[#$>].*$",RegexOption.MULTILINE)," ")
        .replace(Regex("[*_#~>|]")," ").replace(Regex("\\s+")," ").trim()
    fun excerpt(source:String):String {
        val text=clean(source)
        fun fits(value:String)=value.length<=360&&value.toByteArray(Charsets.UTF_8).size<=600
        if(fits(text))return text
        val boundaries=BreakIterator.getSentenceInstance(Locale.US).apply{setText(text)}
        var start=boundaries.first();var end=boundaries.next();var complete=""
        while(end!=BreakIterator.DONE){
            val sentence=text.substring(start,end).trim()
            if(!Regex("[.!?。！？][\"'’”\\])]*$").containsMatchIn(sentence))break
            val candidate=listOf(complete,sentence).filter{it.isNotBlank()}.joinToString(" ")
            if(!fits(candidate))break
            complete=candidate;start=end;end=boundaries.next()
        }
        val withNotice=listOf(complete,more).filter{it.isNotBlank()}.joinToString(" ")
        return if(fits(withNotice))withNotice else complete.ifBlank{more}
    }
    fun chunks(source:String,max:Int=600):List<String> {
        require(max>=2)
        val text=clean(source);val chunks=mutableListOf<String>();var start=0
        while(start<text.length){
            var end=minOf(text.length,start+max)
            if(end<text.length){
                if(Character.isHighSurrogate(text[end-1])&&Character.isLowSurrogate(text[end]))end--
                val window=text.substring(start,end)
                val sentence=Regex("[.!?。！？][\"'’”\\])]*\\s+").findAll(window).lastOrNull()?.range?.last?.plus(1)?:0
                val space=window.lastIndexOf(' ')+1
                end=start+when{sentence>0->sentence;space>0->space;else->window.length}
            }
            chunks.add(text.substring(start,end));start=end
        }
        return chunks
    }
}
