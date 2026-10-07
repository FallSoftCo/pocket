package co.fallsoft.pocket

/** Presentation coverage, not a claim about comprehension. Feed only foreground
 * resumed viewport observations. A long answer is delivered after every portion
 * has actually entered the viewport; background fetches supply no observations. */
internal class CoordinatorReadingCoverage(private val capacity:Int=100) {
    private data class Coverage(val size:Int,val intervals:MutableList<IntRange>)
    private val entries=linkedMapOf<String,Coverage>()
    fun expose(id:String,offset:Int,size:Int,viewportStart:Int,viewportEnd:Int):Boolean {
        if(id.isBlank()||size<=0||viewportEnd<=viewportStart)return false
        val start=(viewportStart.toLong()-offset).coerceIn(0,size.toLong()).toInt()
        val end=(viewportEnd.toLong()-offset).coerceIn(0,size.toLong()).toInt()
        if(start>=end)return false
        val coverage=entries[id]?.takeIf{it.size==size}?:Coverage(size,mutableListOf()).also{entries[id]=it}
        coverage.intervals.add(start until end)
        val sorted=coverage.intervals.sortedBy{it.first}
        val merged=mutableListOf<IntRange>()
        for(part in sorted){
            val previous=merged.lastOrNull()
            if(previous!=null&&part.first<=previous.last+1)merged[merged.lastIndex]=previous.first..maxOf(previous.last,part.last)
            else merged.add(part)
        }
        coverage.intervals.clear();coverage.intervals.addAll(merged)
        while(entries.size>capacity.coerceAtLeast(1))entries.remove(entries.keys.first())
        return merged.size==1&&merged[0].first==0&&merged[0].last==size-1
    }
}
