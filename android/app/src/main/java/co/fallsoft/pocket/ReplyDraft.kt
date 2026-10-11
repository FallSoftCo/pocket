package co.fallsoft.pocket

import java.util.UUID
internal fun replyDraftGeneration(key:String)=Pocket.prefs.getString("$key:generation","legacy")?:"legacy"
/** Human edits get a new identity even if the text is later changed back. */
internal fun saveReplyDraft(key:String,text:String){Pocket.prefs.edit().putString(key,text).putString("$key:generation",UUID.randomUUID().toString()).apply()}
internal data class ClearedReplyDraft(val text:String,val generation:String)
