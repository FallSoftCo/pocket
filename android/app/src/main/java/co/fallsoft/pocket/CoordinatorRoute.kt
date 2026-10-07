package co.fallsoft.pocket

/** A delivery receipt is not a claim that the worker finished its task. */
internal data class CoordinatorRoute(
    val threadId:String,val name:String,val operation:String,val mode:String,val state:String,
    val turnId:String="",val reason:String=""
){
    val label:String get()=when(state){
        "queued"->"Queued"
        "completed"->"Completed"
        "failed"->"Failed"
        "cancelled","canceled"->"Cancelled"
        "pending"->"Pending"
        "submitted"->"Submitted"
        else->if(operation=="read")"Read" else "Pending"
    }
    val correction:String get()="That request belonged in a different session. The intended session is "
}

data class CoordinatorMessage(val id:String,val first:String,val second:String)

internal data class CoordinatorItemExposure(val id:String,val offset:Int,val size:Int,val start:Int,val end:Int)
