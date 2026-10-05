package co.fallsoft.pocket

/** Paragraph alignment is explicit. No word timing or one-to-one word alignment is invented. */
data class BilingualUnit(val target:String,val original:String?,val code:Boolean=false)
private data class ImmersionParagraph(val text:String,val code:Boolean)
private fun immersionParagraphs(text:String):List<ImmersionParagraph>{
    val out=mutableListOf<ImmersionParagraph>();val block=mutableListOf<String>();var fence:Char?=null;var length=0
    val marker=Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    fun flush(){if(block.isNotEmpty()){out+=ImmersionParagraph(block.joinToString("\n"),fence!=null);block.clear()}}
    text.lines().forEach{line->
        val match=marker.matchEntire(line)
        if(fence==null&&match!=null){
            val mark=match.groupValues[1];val info=match.groupValues[2]
            if(mark[0]=='`'&&info.contains('`'))block+=line else {flush();fence=mark[0];length=mark.length;block+=line}
        }else if(fence!=null&&match!=null&&match.groupValues[1][0]==fence&&match.groupValues[1].length>=length&&match.groupValues[2].isBlank()){
            block+=line;flush();fence=null;length=0
        }else if(line.isBlank()&&fence==null)flush() else block+=line
    }
    flush();return out
}
fun bilingualUnits(original:String,target:String):List<BilingualUnit>{
    if(original==target)return listOf(BilingualUnit(original,null))
    val source=immersionParagraphs(original);val translated=immersionParagraphs(target)
    val aligned=source.size==translated.size&&source.indices.all{i->source[i].code==translated[i].code&&(!source[i].code||source[i].text==translated[i].text)}
    if(aligned)return source.indices.map{i->BilingualUnit(translated[i].text,if(source[i].code)null else source[i].text,source[i].code)}
    // Source code is already preserved in the Italian rendering, so the fallback gloss excludes it.
    val gloss=source.filterNot{it.code}.joinToString("\n\n"){it.text}
    return listOf(BilingualUnit(target,gloss.takeIf{it.isNotBlank()}))
}
object ImmersionCommands {
    private val commands=mapOf(
        "git status" to ("Controlla lo stato di Git" to "Checks Git status"),
        "git diff" to ("Confronta le modifiche" to "Compares changes"),
        "git log" to ("Legge la cronologia di Git" to "Reads Git history"),
        "git push" to ("Pubblica le modifiche" to "Publishes changes"),
        "git commit" to ("Registra le modifiche" to "Records changes"),
        "npm test" to ("Esegue i test" to "Runs tests"),
        "npm run build" to ("Compila il progetto" to "Builds the project"),
        "rg" to ("Cerca nel testo" to "Searches text"), "ls" to ("Elenca i file" to "Lists files"),
        "cat" to ("Legge i file" to "Reads files"), "sed" to ("Trasforma il testo" to "Transforms text"),
        "curl" to ("Richiede dati dal server" to "Requests server data"), "ssh" to ("Accede al computer remoto" to "Connects to a remote computer"),
        "node" to ("Esegue JavaScript" to "Runs JavaScript"), "python3" to ("Esegue Python" to "Runs Python"),
        "python" to ("Esegue Python" to "Runs Python"), "cd" to ("Cambia cartella" to "Changes directory"),
        "./gradlew" to ("Esegue le attività Gradle" to "Runs Gradle tasks"),
        "adb" to ("Controlla il dispositivo Android" to "Controls the Android device")
    )
    fun meaning(command:String):Pair<String,String>? {
        val clean=command.trimStart()
        return commands.entries.sortedByDescending{it.key.length}.firstOrNull{clean==it.key||clean.startsWith(it.key+" ")||clean.startsWith(it.key+"\n")}?.value
    }
}

/** Verified UTF-16 source offsets, resolved by the teacher worker from exact quoted phrases. */
data class ImmersionSpan(val start:Int,val end:Int,val source:String,val target:String,val note:String="",val unit:String="phrase",val targetSegments:List<ImmersionTargetSegment> = emptyList())
data class ImmersionSegment(val text:String,val source:String?=null,val note:String="",val unit:String="phrase")
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
fun immersionSegments(original:String,spans:List<ImmersionSpan>):List<ImmersionSegment>{
    if(contextualHybridText(original,spans)==null)return listOf(ImmersionSegment(original))
    val out=mutableListOf<ImmersionSegment>();var cursor=0
    for(span in spans){if(span.start>cursor)out+=ImmersionSegment(original.substring(cursor,span.start));out+=ImmersionSegment(span.target,span.source,span.note,span.unit);cursor=span.end}
    if(cursor<original.length)out+=ImmersionSegment(original.substring(cursor));return out
}

/** Teacher-owned lexical units cover intact Italian. Never derive grammar from spelling. */
data class ImmersionFeature(val name:String,val value:String)
data class ImmersionRelation(val kind:String,val toSegment:Int)
data class ImmersionTargetSegment(val start:Int,val end:Int,val target:String,val meaning:String,val role:String,val features:List<ImmersionFeature> = emptyList(),val relations:List<ImmersionRelation> = emptyList())
data class ImmersionReadingToken(val start:Int,val end:Int,val meaning:String="",val segment:Int?=null)
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
/** The selected phrase expands at its exact reading position; surrounding text keeps its order. */
fun immersionReadingTokens(content:String,span:ImmersionSpan):List<ImmersionReadingToken>? {
    val at=content.indexOf(span.target)
    if(at<0||content.lastIndexOf(span.target)!=at||!alignedTargetValid(span))return null
    val tokens=mutableListOf<ImmersionReadingToken>()
    fun surrounding(start:Int,end:Int){
        Regex("\\S+\\s*|\\s+").findAll(content.substring(start,end)).forEach{m->
            val a=start+m.range.first;val b=start+m.range.last+1
            if((m.value.isBlank()||m.value.trim().all{!it.isLetterOrDigit()})&&tokens.isNotEmpty()){val last=tokens.removeAt(tokens.lastIndex);tokens+=last.copy(end=b)}else tokens+=ImmersionReadingToken(a,b)
        }
    }
    surrounding(0,at)
    span.targetSegments.forEachIndexed{index,s->
        val a=at+s.start;val b=at+s.end
        if((s.role in listOf("separator","punctuation")||s.target.isBlank())&&tokens.isNotEmpty()){val last=tokens.removeAt(tokens.lastIndex);tokens+=last.copy(end=b)}else tokens+=ImmersionReadingToken(a,b,s.meaning,index)
    }
    surrounding(at+span.target.length,content.length)
    return tokens
}
