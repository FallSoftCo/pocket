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
data class ImmersionSpan(val start:Int,val end:Int,val source:String,val target:String,val note:String="",val unit:String="phrase")
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

/** Help is inserted only beside one unambiguous displayed constituent, never into its source. */
fun inlineImmersionHelp(content:String,span:ImmersionSpan):Pair<Int,String>? {
    val at=content.indexOf(span.target)
    if(at<0||content.lastIndexOf(span.target)!=at||span.target==span.source)return null
    val note=span.note.trim().takeIf{it.isNotEmpty()}?.let{" · "+it}.orEmpty()
    return (at+span.target.length) to (" ["+span.source+note+"]")
}
