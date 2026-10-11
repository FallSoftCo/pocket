package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One source-bound recovery surface; local removal never cancels backend work. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ClientReplyRecovery(intent:ClientReplyIntent,onEdit:(String)->Unit){
    var confirm by remember(intent.id){mutableStateOf(false)}
    val busy=intent.id in PocketReplies.busy
    val label=if(intent.id in PocketReplies.dispatching)"Sending"else when(intent.state){"queued"->"Queued";"held"->"Waiting in queue";"sending"->"Sending";"failed"->"Message failed";else->"Delivery not confirmed"}
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        BilingualLabel(label,color=if(intent.state in admittedReplyStates)Mint else Coral)
        ImmersionText("saved-reply:"+intent.id,intent.text,rescue=false,fontSize=14.sp)
        if(intent.message.isNotBlank())WorkflowText(intent.message,color=Coral,fontSize=13.sp)
        FlowRow{
            TextButton({PocketReplies.check(intent)},enabled=!busy){BilingualLabel(if(busy)"Checking…" else "Check status")}
            if(intent.state !in admittedReplyStates)TextButton({confirm=true},enabled=!busy&&!Pocket.sending){BilingualLabel("Retry")}
            TextButton({onEdit(intent.text)},enabled=!busy){BilingualLabel("Edit draft")}
            TextButton({PocketReplies.forget(intent)},enabled=!busy){BilingualLabel("Remove saved copy",color=Coral)}
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={BilingualLabel("Check and retry this message?")},text={WorkflowText("Codex may already have received it. We’ll check its saved receipt first and keep the same message identity. A missing receipt cannot guarantee that retrying won’t duplicate work.")},confirmButton={TextButton({confirm=false;PocketReplies.retry(intent)}){BilingualLabel("Check and retry")}},dismissButton={TextButton({confirm=false}){BilingualLabel("Cancel")}})
}
