package co.fallsoft.pocket

/** Stable local identities are independent of hostname, credentials and session IDs. */
data class EnvironmentIdentity(val id:String,val name:String,val local:Boolean=false) {
    init { require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); require(name.isNotBlank()) }
    fun key(name:String)=when(id){"workstation"->name;"phone"->"local:$name";else->"environment:$id:$name"}
    fun session(thread:String)="$id:$thread"
}
/** A request captures its destination before dispatch and never resolves against a later selection. */
data class EnvironmentRequest(val environment:EnvironmentIdentity,val endpoint:String,val token:String) {
    fun matches(other:EnvironmentRequest)=environment.id==other.environment.id&&endpoint==other.endpoint&&token==other.token
    override fun toString()="EnvironmentRequest(${environment.id}, credentials redacted)"
}

internal fun isPhoneEnvironment(endpoint:String)=endpoint.trimEnd('/') in listOf("http://127.0.0.1:18880","http://localhost:18880")

internal fun automaticReplyEligible(state:String)=state !in setOf("unknown","failed","cancelled","canceled")
