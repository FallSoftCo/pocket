package co.fallsoft.pocket

// Prototype tuning from user feedback. These are not human-perception thresholds.
internal const val IMMERSION_HANDOFF_DURATION_MS=4200L
internal const val IMMERSION_HANDOFF_AT_MS=1500L
internal data class ImmersionInkPhase(val incoming:Boolean,val opacity:Float=1f,val cue:Float=0f)
internal fun immersionInkPhase(progress:Float):ImmersionInkPhase {
    val elapsed=progress.coerceIn(0f,1f)*IMMERSION_HANDOFF_DURATION_MS
    fun smooth(value:Float):Float {val x=value.coerceIn(0f,1f);return x*x*(3f-2f*x)}
    val cue=when {
        elapsed<700f -> smooth(elapsed/700f)
        elapsed<3700f -> 1f
        else -> 1f-smooth((elapsed-3700f)/500f)
    }
    return ImmersionInkPhase(elapsed>=IMMERSION_HANDOFF_AT_MS,1f,cue)
}
