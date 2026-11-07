package co.fallsoft.pocket

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** List-local confirmation captures the card, not whichever chat is selected. */
@Composable internal fun SessionArchiveAction(task:Task,onArchive:(()->Unit)?,onRestore:()->Unit){
    var confirm by remember(task.id){mutableStateOf(false)}
    if(task.archived){
        IconButton(onRestore,Modifier.size(48.dp).testTag("restore-session:"+task.id)){
            SymbolIcon("Unarchive","Restore conversation",Modifier.size(24.dp),tint=Mint)
        }
    }else if(onArchive!=null){
        IconButton({confirm=true},Modifier.size(48.dp).testTag("archive-session:"+task.id)){
            Icon(Icons.Rounded.Archive,"Archive conversation",Modifier.size(24.dp),tint=Muted)
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},
        title={BilingualLabel("Archive conversation?")},
        text={WorkflowText(task.title+"\n\nHide from the main list and stop its notifications. Queued messages are held. History and drafts stay available. Restore from Archived in Find sessions. A running turn must be stopped first.")},
        confirmButton={TextButton({confirm=false;onArchive?.invoke()},Modifier.testTag("confirm-session-archive")){BilingualLabel("Archive")}},
        dismissButton={TextButton({confirm=false}){BilingualLabel("Cancel")}})
}
