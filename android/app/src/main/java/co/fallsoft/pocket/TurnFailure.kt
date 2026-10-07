package co.fallsoft.pocket

internal enum class TurnFailureKind(val title:String,val explanation:String) {
    CAPACITY("Model at capacity","Codex stopped because the selected model is at capacity. Earlier progress remains in this conversation."),
    NETWORK_PERMISSION("Codex network permission revoked","The Codex runtime reported that its application network permission was revoked. This is separate from the phone’s connection. Earlier progress remains here; reconcile actions before continuing."),
    OTHER("Codex turn stopped","Earlier progress remains in this conversation. Review the error and reconcile actions before continuing.")
}

internal fun turnFailureKind(message:String,details:String=""):TurnFailureKind {
    val text="$message\n$details".lowercase()
    return when {
        "application network permission was revoked" in text -> TurnFailureKind.NETWORK_PERMISSION
        "selected model is at capacity" in text || "model is at capacity" in text -> TurnFailureKind.CAPACITY
        else -> TurnFailureKind.OTHER
    }
}

internal const val SAFE_CONTINUATION="Continue from the preserved progress; first reconcile any actions already taken. Do not repeat submissions."
internal fun continuationDraft(existing:String):String=when {
    SAFE_CONTINUATION in existing -> existing
    existing.isBlank() -> SAFE_CONTINUATION
    else -> "$existing\n\n$SAFE_CONTINUATION"
}
