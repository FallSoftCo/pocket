package co.fallsoft.pocket

import kotlin.math.max

data class ImmersionCycleTiming(val sourceMs:Long,val targetMs:Long,val staggerMs:Long){val periodMs get()=sourceMs+targetMs}
data class ImmersionCyclePhase(val original:Boolean,val untilChangeMs:Long)
data class ImmersionReserveWord(val source:String,val target:String)
fun immersionCycleTiming(identity:String,source:String,target:String):ImmersionCycleTiming {
    fun words(text:String)=Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*(?:['’][\\p{L}\\p{M}\\p{N}]+)*").findAll(text).count().toLong()
    val sourceMs=max(4000L,words(source)*300L)
    val targetMs=maxOf(8000L,words(target)*450L+4000L,sourceMs+4000L)
    val period=sourceMs+targetMs
    var seed=identity.hashCode()
    seed=seed xor (seed ushr 16);seed*= -2048144789
    seed=seed xor (seed ushr 13);seed*= -1028477387
    seed=seed xor (seed ushr 16)
    val stagger=Math.floorMod(seed.toLong(),period)
    return ImmersionCycleTiming(sourceMs,targetMs,stagger)
}
fun immersionCyclePhase(nowMs:Long,timing:ImmersionCycleTiming):ImmersionCyclePhase {
    val at=Math.floorMod(nowMs+timing.staggerMs,timing.periodMs)
    return if(at<timing.targetMs)ImmersionCyclePhase(false,timing.targetMs-at) else ImmersionCyclePhase(true,timing.periodMs-at)
}
/** Slot pairing is geometric reservation only; it claims no linguistic word alignment. */
fun immersionReserveWords(source:String,target:String):List<ImmersionReserveWord> {
    fun words(text:String)=Regex("\\S+\\s*|\\s+").findAll(text).map{it.value}.toList()
    val a=words(source);val b=words(target)
    return List(max(a.size,b.size)){ImmersionReserveWord(a.getOrElse(it){""},b.getOrElse(it){""})}
}
fun immersionCyclePlan(plan:ImmersionPresentation,originals:Set<Int>):ImmersionPresentation {
    if(contextualHybridText(plan.source,plan.spans)!=plan.text)return ImmersionPresentation(plan.source,plan.source,false)
    val spans=plan.spans.mapIndexed{index,span->if(index in originals)span.copy(target=span.source)else span}
    return plan.copy(text=contextualHybridText(plan.source,spans)?:plan.source,spans=spans)
}

/** Recover an exact captured mixed flow without relying on the current wall-clock phases. */
fun immersionCapturedOriginals(plan:ImmersionPresentation,captured:String):Set<Int>? {
    if(plan.spans.isEmpty()||contextualHybridText(plan.source,plan.spans)!=plan.text)return null
    val prefix=plan.source.substring(0,plan.spans.first().start)
    val anchors=if(prefix.isNotEmpty())listOf(prefix)else listOf(plan.spans.first().source,plan.spans.first().target).distinct()
    val starts=linkedSetOf<Int>()
    anchors.filter{it.isNotEmpty()}.forEach{anchor->var at=captured.indexOf(anchor);while(at>=0){starts.add(at);at=captured.indexOf(anchor,at+1)}}
    fun match(index:Int,at:Int,originals:Set<Int>):Set<Int>? {
        if(index==plan.spans.size){val tail=plan.source.substring(plan.spans.last().end);return originals.takeIf{captured.startsWith(tail,at)}}
        val span=plan.spans[index]
        val previous=if(index==0)0 else plan.spans[index-1].end
        val gap=plan.source.substring(previous,span.start)
        if(!captured.startsWith(gap,at))return null
        val position=at+gap.length
        for(original in listOf(false,true)){
            val word=if(original)span.source else span.target
            if(captured.startsWith(word,position)){
                val result=match(index+1,position+word.length,if(original)originals+index else originals)
                if(result!=null)return result
            }
        }
        return null
    }
    return starts.firstNotNullOfOrNull{match(0,it,emptySet())}
}

/** Natural phrase chunks; paired slots reserve geometry without inventing lexical alignment. */
fun immersionReserveChunks(source:String,target:String,maxWidth:Int,width:(String)->Int):List<ImmersionReserveWord>{
    fun chunks(text:String):List<String>{
        if(text.isEmpty())return emptyList()
        if(width(text)<=maxWidth)return listOf(text)
        val result=mutableListOf<String>();var current=""
        Regex("\\S+\\s*|\\s+").findAll(text).forEach{token->
            if(current.isNotEmpty()&&width(current+token.value)>maxWidth){result.add(current);current=""}
            current+=token.value
        }
        if(current.isNotEmpty())result.add(current)
        return result
    }
    val a=chunks(source);val b=chunks(target)
    return List(max(a.size,b.size)){ImmersionReserveWord(a.getOrElse(it){""},b.getOrElse(it){""})}
}

/** Assemble alternate native paragraph forms from exact display ranges, without word padding. */
fun immersionParagraphForm(text:String,ranges:List<Triple<Int,Int,String>>):String?{
    var cursor=0
    return buildString{ranges.sortedBy{it.first}.forEach{(start,end,replacement)->
        if(start<cursor||end<start||end>text.length)return null
        append(text,cursor,start);append(replacement);cursor=end
    };append(text,cursor,text.length)}
}

/** Retained for historical reservation tests. */
fun immersionPhraseEnd(text:String,end:Int):Int {
    var at=end.coerceIn(0,text.length)
    while(at<text.length&&text[at] in ",.;:!?…)]}»”")at++
    return at
}

/** One deadline per visible owner, not a ticking composition clock. Interruptions do not
 * restart an entire reading hold; an overdue cue still receives a safe resume breath. */
internal class ImmersionCueDeadline(private val clock:()->Long={System.nanoTime()/1000000L}) {
    private var due:Long?=null
    fun waitMs(initialMs:Long,resumeFloorMs:Long=1000L):Long {
        val now=clock()
        if(due==null)due=now+initialMs
        return (due!!-now).coerceAtLeast(resumeFloorMs)
    }
    fun hold(durationMs:Long){due=clock()+durationMs}
}

/** The reader consumes the visible phrase, not the size of the containing transcript. */
internal fun immersionVisibleTiming(identity:String,spans:List<ImmersionSpan>,cursor:Int,visible:Set<String>):ImmersionCycleTiming? {
    val index=immersionNextVisible(spans,cursor,visible)
    if(index<0)return null
    val span=spans[index]
    return immersionCycleTiming(identity+span.start,span.source,span.target)
}
