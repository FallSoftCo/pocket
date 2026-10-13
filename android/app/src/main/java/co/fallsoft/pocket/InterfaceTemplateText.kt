package co.fallsoft.pocket

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import java.util.Locale

/** Authored grammar, literal parameters: no model request for changing IDs/counts/paths. */
internal enum class InterfaceTemplate(val source:String,val italian:String){
    TEAM_CAPTION_TEAM("Team","Squadra"),
    TEAM_CAPTION_TASK("Task","Compito"),
    TEAM_CAPTION_TO("To","A"),
    TEAM_CAPTION_AGENT("Agent","Agente"),
    TEAM_EVENT_MESSAGE("%1\$s · message","%1\$s · messaggio"),
    TEAM_EVENT_ATTENTION("%1\$s · attention","%1\$s · attenzione"),
    TEAM_EVENT_APPROVAL("%1\$s · approval","%1\$s · approvazione"),
    TEAM_CHOICE_TEAM("Team: %1\$s","Squadra: %1\$s"),
    TEAM_CHOICE_TASK("Task: %1\$s","Compito: %1\$s"),
    TEAM_CHOICE_TO("To: %1\$s","Destinatario: %1\$s"),
    TEAM_CHOICE_AGENT("Agent: %1\$s","Agente: %1\$s"),
    TEAM_CHOICE_COORDINATOR("To: Coordinator","Destinatario: Coordinatore"),
    TEAM_REPLY_CLEAR("Reply to #%1\$s · clear","Rispondi a #%1\$s · cancella"),
    TEAM_MESSAGE_RECIPIENT("Message %1\$s","Messaggio a %1\$s"),
    TEAM_ROOM_TASK("Room · %1\$s","Stanza · %1\$s"),
    TEAM_DIRECT_TASK("Direct · %1\$s · %2\$s","Diretto · %1\$s · %2\$s"),
    TEAM_ROOM_LOADING("Room · Loading…","Stanza · Caricamento…"),
    TEAM_DIRECT_LOADING("Direct · %1\$s · Loading…","Diretto · %1\$s · Caricamento…"),
    TEAM_COORDINATOR("Losangelex · %1\$s · Coordinator","Losangelex · %1\$s · Coordinatore"),
    TEAM_ATTENTION("Needs you · %1\$s","Serve il tuo intervento · %1\$s"),
    CREDIT_OBSERVATION("%1\$s credits · %2\$s","%1\$s crediti · %2\$s"),
    COMMAND_RESULTS("%1\$s command results · %2\$s exited successfully","%1\$s risultati dei comandi · %2\$s terminati correttamente"),
    FILE_CHANGES("Recorded file changes · %1\$s","Modifiche ai file registrate · %1\$s"),
    GENERATED_ARTIFACT("Generated artifact: %1\$s","Risorsa generata: %1\$s"),
    RECORDED_MESSAGE("Recorded message · %1\$s","Messaggio registrato · %1\$s"),
    SOURCE_MESSAGE("Source message %1\$s","Messaggio originale %1\$s"),
    AGENT_STATUS("Agent output · %1\$s","Risposta dell’agente · %1\$s");
    fun original(vararg values:Any)=String.format(Locale.ROOT,source,*values)
    fun target(vararg values:Any)=String.format(Locale.ITALIAN,italian,*values)
    fun scaffold(vararg values:Any):ImmersionSpan {
        fun words(vararg entries:String)=entries.flatMapIndexed{index,entry->
            val pair=entry.split("|",limit=2).let{parts->parts[0] to parts.getOrElse(1){parts[0]}}
            if(index==0)listOf(pair)else listOf(" " to " ",pair)
        }
        fun literal(index:Int)=values[index].toString().let{it to it}
        val units=when(this){
            TEAM_CAPTION_TEAM->words("Squadra|Team")
            TEAM_CAPTION_TASK->words("Compito|Task")
            TEAM_CAPTION_TO->words("A|To")
            TEAM_CAPTION_AGENT->words("Agente|Agent")
            TEAM_EVENT_MESSAGE->listOf(literal(0)," · " to " · ")+words("messaggio|message")
            TEAM_EVENT_ATTENTION->listOf(literal(0)," · " to " · ")+words("attenzione|attention")
            TEAM_EVENT_APPROVAL->listOf(literal(0)," · " to " · ")+words("approvazione|approval")
            TEAM_CHOICE_TEAM->words("Squadra:|Team:")+listOf(" " to " ",literal(0))
            TEAM_CHOICE_TASK->words("Compito:|Task:")+listOf(" " to " ",literal(0))
            TEAM_CHOICE_TO->words("Destinatario:|Recipient:")+listOf(" " to " ",literal(0))
            TEAM_CHOICE_AGENT->words("Agente:|Agent:")+listOf(" " to " ",literal(0))
            TEAM_CHOICE_COORDINATOR->words("Destinatario:|Recipient:","Coordinatore|Coordinator")
            TEAM_REPLY_CLEAR->words("Rispondi|Reply","a|to")+listOf(" #" to " #",literal(0)," · " to " · ")+words("cancella|clear")
            TEAM_MESSAGE_RECIPIENT->words("Messaggio|Message","a|to")+listOf(" " to " ",literal(0))
            TEAM_ROOM_TASK->words("Stanza|Room","·")+listOf(" " to " ",literal(0))
            TEAM_DIRECT_TASK->words("Diretto|Direct","·")+listOf(" " to " ",literal(0)," · " to " · ",literal(1))
            TEAM_ROOM_LOADING->words("Stanza|Room","·","Caricamento…|Loading…")
            TEAM_DIRECT_LOADING->words("Diretto|Direct","·")+listOf(" " to " ",literal(0)," · " to " · ")+words("Caricamento…|Loading…")
            TEAM_COORDINATOR->words("Losangelex","·")+listOf(" " to " ",literal(0)," · " to " · ")+words("Coordinatore|Coordinator")
            TEAM_ATTENTION->words("Serve|Needs","il","tuo","intervento|input","·")+listOf(" " to " ",literal(0))
            CREDIT_OBSERVATION->listOf(values[0].toString() to values[0].toString()," " to " ","crediti" to "credits"," · " to " · ",values[1].toString() to values[1].toString())
            COMMAND_RESULTS->listOf(literal(0)," " to " ")+words("risultati|results","dei","comandi|commands","·")+listOf(" " to " ",literal(1)," " to " ")+words("terminati|exited","correttamente|successfully")
            FILE_CHANGES->words("Modifiche|Changes","ai","file|files","registrate|recorded","·")+listOf(" " to " ",values[0].toString() to values[0].toString())
            GENERATED_ARTIFACT->words("Risorsa|Artifact","generata:|generated:")+listOf(" " to " ",values[0].toString() to values[0].toString())
            RECORDED_MESSAGE->words("Messaggio|Message","registrato|recorded","·")+listOf(" " to " ",values[0].toString() to values[0].toString())
            SOURCE_MESSAGE->words("Messaggio|Message","originale|source")+listOf(" " to " ",values[0].toString() to values[0].toString())
            AGENT_STATUS->words("Risposta|Output","dell’agente|dell’agent","·")+listOf(" " to " ",values[0].toString() to values[0].toString())
        }
        return authoredImmersionScaffold(original(*values),units)
    }
}
@Composable internal fun InterfaceTemplateText(
    template:InterfaceTemplate,vararg values:Any,
    modifier:Modifier=Modifier,style:TextStyle=LocalTextStyle.current,maxLines:Int=Int.MAX_VALUE,
    overflow:androidx.compose.ui.text.style.TextOverflow=androidx.compose.ui.text.style.TextOverflow.Clip
){
    InterfaceText(template.original(*values),modifier=modifier,style=style,maxLines=maxLines,overflow=overflow,targetOverride=template.target(*values),scaffoldOverride=template.scaffold(*values))
}
