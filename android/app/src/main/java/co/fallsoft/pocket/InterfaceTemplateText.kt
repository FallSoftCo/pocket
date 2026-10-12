package co.fallsoft.pocket

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import java.util.Locale

/** Authored grammar, literal parameters: no model request for changing IDs/counts/paths. */
internal enum class InterfaceTemplate(val source:String,val italian:String){
    COMMAND_RESULTS("%1\$s command results · %2\$s exited successfully","%1\$s risultati dei comandi · %2\$s terminati correttamente"),
    FILE_CHANGES("Recorded file changes · %1\$s","Modifiche ai file registrate · %1\$s"),
    GENERATED_ARTIFACT("Generated artifact: %1\$s","Risorsa generata: %1\$s"),
    RECORDED_MESSAGE("Recorded message · %1\$s","Messaggio registrato · %1\$s"),
    SOURCE_MESSAGE("Source message %1\$s","Messaggio originale %1\$s"),
    AGENT_STATUS("Agent output · %1\$s","Risposta dell’agente · %1\$s");
    fun original(vararg values:Any)=String.format(Locale.ROOT,source,*values)
    fun target(vararg values:Any)=String.format(Locale.ITALIAN,italian,*values)
}
@Composable internal fun InterfaceTemplateText(
    template:InterfaceTemplate,vararg values:Any,
    modifier:Modifier=Modifier,style:TextStyle=LocalTextStyle.current
){
    InterfaceText(template.original(*values),modifier=modifier,style=style,targetOverride=template.target(*values))
}
