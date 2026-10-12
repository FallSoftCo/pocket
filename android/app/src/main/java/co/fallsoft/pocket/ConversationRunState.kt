package co.fallsoft.pocket

internal enum class ConversationRunState(val label:String,val icon:String,val spinning:Boolean=false) {
    NEEDS_YOU("Needs you","User"), WORKING("Working","Codex",true),
    THINKING("Thinking","Psychology",true), STARTING("Starting","Codex",true),
    LOADING("Loading","Codex",true), YOUR_TURN("Ready","Check"), FAILED("Failed","Stop"), STOPPED("Stopped","Stop"), CHECKING("Checking","Codex",true)
}

internal fun conversationRunState(pending:Int,taskStatus:String,threadStatus:String,turnStatus:String,thinking:Boolean,loaded:Boolean,loading:Boolean):ConversationRunState=when {
    pending>0 -> ConversationRunState.NEEDS_YOU
    taskStatus=="pending" -> ConversationRunState.STARTING
    taskStatus=="active"||threadStatus=="active"||turnStatus=="inProgress" -> if(thinking)ConversationRunState.THINKING else ConversationRunState.WORKING
    !loaded -> if(loading)ConversationRunState.LOADING else ConversationRunState.CHECKING
    turnStatus=="failed" -> ConversationRunState.FAILED
    turnStatus=="interrupted" -> ConversationRunState.STOPPED
    else -> ConversationRunState.YOUR_TURN
}
