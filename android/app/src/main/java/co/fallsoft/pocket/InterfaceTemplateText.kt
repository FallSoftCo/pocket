package co.fallsoft.pocket

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import java.util.Locale

/** Authored grammar, literal parameters: no model request for changing IDs/counts/paths. */
internal enum class InterfaceTemplate(val source:String,val italian:String){
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
        val units=when(this){
            CREDIT_OBSERVATION->listOf(values[0].toString() to values[0].toString()," " to " ","crediti" to "credits"," · " to " · ",values[1].toString() to values[1].toString())
            COMMAND_RESULTS->words(values[0].toString(),"risultati|results","dei","comandi|commands","·",values[1].toString(),"terminati|exited","correttamente|successfully")
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
    modifier:Modifier=Modifier,style:TextStyle=LocalTextStyle.current
){
    InterfaceText(template.original(*values),modifier=modifier,style=style,targetOverride=template.target(*values),scaffoldOverride=template.scaffold(*values))
}
