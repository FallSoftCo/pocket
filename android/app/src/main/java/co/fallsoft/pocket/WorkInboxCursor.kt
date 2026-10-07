package co.fallsoft.pocket

/** Preserve loaded older history while filling a gap after a long absence. */
internal class WorkInboxCursor {
    var before:Long?=null;private set
    var hasEarlier=false;private set
    private var restore:Pair<Long?,Boolean>?=null
    fun reset(){before=null;hasEarlier=false;restore=null}
    fun accept(pageBefore:Long?,pageEarlier:Boolean,older:Boolean,cached:Boolean,overlaps:Boolean){
        if(older&&restore!=null&&overlaps){val saved=restore!!;before=saved.first;hasEarlier=saved.second;restore=null;return}
        if(!older&&cached&&overlaps)return
        if(!older&&cached&&!overlaps&&before!=null&&pageEarlier&&restore==null)restore=before to hasEarlier
        before=pageBefore;hasEarlier=pageEarlier
    }
}
