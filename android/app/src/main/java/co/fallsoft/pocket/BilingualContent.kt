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
