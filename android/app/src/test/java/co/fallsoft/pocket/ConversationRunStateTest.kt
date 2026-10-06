package co.fallsoft.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRunStateTest {
    private fun state(pending:Int=0,task:String="",thread:String="",turn:String="",thinking:Boolean=false,loaded:Boolean=true,loading:Boolean=false)=conversationRunState(pending,task,thread,turn,thinking,loaded,loading)
    @Test fun questionsAreVisibleEvenWhenRuntimeCallsThreadActive(){assertEquals(ConversationRunState.NEEDS_YOU,state(pending=1,task="active",turn="inProgress"))}
    @Test fun streamedTurnRemainsWorkingBeforeTaskListCatchesUp(){assertEquals(ConversationRunState.WORKING,state(task="idle",turn="inProgress"));assertEquals(ConversationRunState.THINKING,state(thread="active",thinking=true))}
    @Test fun unloadedConversationDoesNotPretendItIsWaitingForInput(){assertEquals(ConversationRunState.LOADING,state(loaded=false,loading=true));assertEquals(ConversationRunState.CHECKING,state(loaded=false))}
    @Test fun knownActivityIsVisibleBeforeHistoryLoads(){assertEquals(ConversationRunState.STARTING,state(task="pending",loaded=false));assertEquals(ConversationRunState.WORKING,state(task="active",loaded=false,loading=true))}
    @Test fun completionAndInterruptionAreDistinct(){assertEquals(ConversationRunState.YOUR_TURN,state(task="idle",turn="completed"));assertEquals(ConversationRunState.STOPPED,state(turn="interrupted"));assertEquals(ConversationRunState.STOPPED,state(turn="failed"))}
}
