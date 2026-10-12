package co.fallsoft.pocket

import kotlin.math.roundToLong

/**
 * Continuous viewport wave. Local speed never depends on paragraph length.
 * Tuning is a visual prototype, not a human reading-speed threshold.
 *
 * Callers supply window-space coordinates in dp, not pixels or line counts.
 * One elapsed clock must be shared by controls and body text. Drawing consumes
 * frames; this policy creates no timer, model request or lifecycle work.
 */
internal data class ImmersionReadingWave(
    val sweepMs:Long=1200L,
    val holdMs:Long=3000L,
    val verticalMsPerDp:Double=350.0/26.0
) {
    init {
        require(sweepMs>0 && holdMs>=0)
        require(sweepMs<=Long.MAX_VALUE/4 && holdMs<=Long.MAX_VALUE/4)
        require(verticalMsPerDp.isFinite() && verticalMsPerDp>=0)
    }
    val halfPeriodMs:Long=sweepMs+holdMs
    val periodMs:Long=halfPeriodMs*2

    /** Offset is spatial: separate composables on one row share the frontier. */
    fun onsetMs(lineTopDp:Double):Long {
        require(lineTopDp.isFinite())
        return (lineTopDp*verticalMsPerDp).roundToLong()
    }

    fun frame(elapsedMs:Long,lineTopDp:Double):ImmersionReadingWaveFrame {
        // Reduce before subtracting so long-running clocks cannot overflow.
        val time=Math.floorMod(elapsedMs,periodMs)
        val onset=Math.floorMod(onsetMs(lineTopDp),periodMs)
        val local=Math.floorMod(time-onset,periodMs)
        val towardSupport=local<halfPeriodMs
        val phase=if(towardSupport)local else local-halfPeriodMs
        val progress=(phase.toDouble()/sweepMs).coerceIn(0.0,1.0).toFloat()
        return ImmersionReadingWaveFrame(towardSupport,progress)
    }
}

internal data class ImmersionReadingWaveFrame(val towardSupport:Boolean,val progress:Float) {
    /**
     * Complementary coverage at a position along the line. A shared horizontal
     * extent prevents individual short fragments from finishing early.
     */
    fun supportCoverage(readingPosition:Float,feather:Float=.06f):Float {
        require(readingPosition.isFinite())
        require(feather.isFinite() && feather>0f)
        val frontier=-feather+(1f+2f*feather)*progress
        val incoming=((frontier+feather-readingPosition)/(2f*feather)).coerceIn(0f,1f)
        return if(towardSupport)incoming else 1f-incoming
    }
}
