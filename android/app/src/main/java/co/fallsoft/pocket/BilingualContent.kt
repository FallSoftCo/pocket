package co.fallsoft.pocket

/** Verified UTF-16 source offsets, resolved by the teacher worker from exact quoted phrases. */
data class ImmersionSpan(val start:Int,val end:Int,val source:String,val target:String,val note:String="",val unit:String="phrase",val targetSegments:List<ImmersionTargetSegment> = emptyList())
data class ImmersionPresentation(val text:String,val source:String,val current:Boolean,val spans:List<ImmersionSpan> = emptyList())
fun immersionSourceHash(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
/** Never splice a previous plan into a new source or infer word-level alignment. */
fun contextualHybridText(original:String,spans:List<ImmersionSpan>):String? {
    var cursor=0;val result=StringBuilder()
    for(span in spans){
        if(span.start<cursor||span.end<=span.start||span.end>original.length||original.substring(span.start,span.end)!=span.source||span.target.isBlank())return null
        result.append(original.substring(cursor,span.start));result.append(span.target);cursor=span.end
    }
    result.append(original.substring(cursor));return result.toString()
}
/** One source-ordered text flow. Rescue replaces only the selected phrase in place. */
fun immersionReplacementText(plan:ImmersionPresentation,selected:ImmersionSpan?=null,showOriginal:Boolean=false):String {
    if(showOriginal)return plan.source
    if(contextualHybridText(plan.source,plan.spans)!=plan.text)return plan.source
    if(selected==null||selected !in plan.spans)return plan.text
    return contextualHybridText(plan.source,plan.spans.map{if(it==selected)it.copy(target=it.source)else it})?:plan.source
}
/** Teacher-owned lexical units cover intact Italian. Never derive grammar from spelling. */
data class ImmersionFeature(val name:String,val value:String)
data class ImmersionRelation(val kind:String,val toSegment:Int)
data class ImmersionTargetSegment(val start:Int,val end:Int,val target:String,val meaning:String,val role:String,val features:List<ImmersionFeature> = emptyList(),val relations:List<ImmersionRelation> = emptyList())
fun alignedTargetValid(span:ImmersionSpan):Boolean {
    if(span.targetSegments.isEmpty())return false
    var cursor=0
    for(segment in span.targetSegments){
        if(segment.start!=cursor||segment.end<=segment.start||segment.end>span.target.length||span.target.substring(segment.start,segment.end)!=segment.target)return false
        if(segment.relations.any{it.toSegment !in span.targetSegments.indices})return false
        if(segment.target.isBlank()&&segment.meaning.isNotBlank())return false
        cursor=segment.end
    }
    return cursor==span.target.length
}
fun agreementSegments(span:ImmersionSpan):Set<Int> = span.targetSegments.flatMapIndexed{index,s->s.relations.filter{it.kind=="agreesWith"}.flatMap{listOf(index,it.toSegment)}}.toSet()
/** Shared visual groups come only from explicit validated agreement edges. */
fun agreementGroups(span:ImmersionSpan):Map<Int,Int> {
    if(!alignedTargetValid(span))return emptyMap()
    val edges=mutableMapOf<Int,MutableSet<Int>>()
    span.targetSegments.forEachIndexed{index,s->s.relations.filter{it.kind=="agreesWith"}.forEach{r->
        val to=r.toSegment
        if(to!=index&&s.role !in listOf("separator","punctuation")&&span.targetSegments[to].role !in listOf("separator","punctuation")){
            edges.getOrPut(index){mutableSetOf()}.add(to);edges.getOrPut(to){mutableSetOf()}.add(index)
        }
    }}
    val groups=mutableMapOf<Int,Int>();var group=0
    for(start in edges.keys.sorted())if(start !in groups){
        val pending=java.util.ArrayDeque<Int>();pending.add(start)
        while(pending.isNotEmpty()){val node=pending.removeFirst();if(node in groups)continue;groups[node]=group;edges[node].orEmpty().sorted().forEach{if(it !in groups)pending.add(it)}}
        group++
    }
    return groups
}
fun grammarCue(segment:ImmersionTargetSegment):String = when {
    segment.relations.any{it.kind=="negates"}->"negation"
    segment.features.any{(it.name=="tense"&&it.value=="conditional")||(it.name=="mood"&&it.value in listOf("conditional","subjunctive","imperative"))}->"modality"
    segment.features.any{it.name=="aspect"&&it.value !in listOf("","none")}->"aspect"
    segment.features.any{it.name=="tense"&&it.value in listOf("past","imperfect","perfect","pluperfect")}->"past"
    else->segment.role
}
