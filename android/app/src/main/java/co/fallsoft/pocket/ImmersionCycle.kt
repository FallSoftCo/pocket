package co.fallsoft.pocket

import kotlin.math.max

data class ImmersionCycleTiming(val sourceMs:Long,val targetMs:Long,val staggerMs:Long){val periodMs get()=sourceMs+targetMs}
data class ImmersionCyclePhase(val original:Boolean,val untilChangeMs:Long)
data class ImmersionReserveWord(val source:String,val target:String)
fun immersionCycleTiming(identity:String,source:String,target:String):ImmersionCycleTiming {
    fun reading(text:String)=((Regex("\\S+").findAll(text).count()*320L)+text.codePointCount(0,text.length)*28L).coerceIn(2400L,8500L)
    val sourceMs=reading(source)
    val targetMs=max(sourceMs+1400L,reading(target)+1600L)
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
