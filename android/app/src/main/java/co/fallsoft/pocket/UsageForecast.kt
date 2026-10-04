package co.fallsoft.pocket

// Estimates the current reset window only; a reset or increased allowance invalidates history.
data class UsageSample(val at:Long,val remaining:Double,val reset:Long)
object UsageForecast {
    fun record(history:List<UsageSample>,usage:WeeklyUsage):List<UsageSample> {
        val remaining=usage.remainingPercent?:return history
        val reset=usage.resetsAt?:return history
        if(usage.stale||usage.updatedAt<=0)return history
        val newest=history.lastOrNull()
        if(newest!=null&&usage.updatedAt<=newest.at)return history
        val sameWindow=history.filter{it.reset==reset&&usage.updatedAt-it.at<=86400000L}
        val clean=if(newest!=null&&remaining>newest.remaining+0.1)emptyList() else sameWindow
        if(clean.isNotEmpty()&&usage.updatedAt-clean.last().at<300000)return clean
        return (clean+UsageSample(usage.updatedAt,remaining,reset)).takeLast(289)
    }
    fun deadline(history:List<UsageSample>,usage:WeeklyUsage,now:Long,connected:Boolean):Long? {
        if(usage.isStale(now,connected))return null
        val first=history.firstOrNull()?:return null
        val last=history.lastOrNull()?:return null
        val remaining=usage.remainingPercent?:return null
        val reset=usage.resetsAt?:return null
        val elapsed=last.at-first.at
        val spent=first.remaining-last.remaining
        if(first.reset!=reset||last.reset!=reset||elapsed<1800000||spent<1.0||remaining<=0)return null
        val projected=now+(remaining*elapsed/spent).toLong()
        return projected.takeIf{it<reset*1000}
    }
}
