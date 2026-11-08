package co.fallsoft.pocket

import java.util.Locale

/** The gateway account observation is not a room/agent quota. Never infer that
 * two gateways or two pairings use the same provider account. No raw credential
 * is persisted in this cache ownership marker.
 */
internal fun accountUsageOwner(source:EnvironmentRequest)=source.environment.id+":"+backendOwner(source.endpoint,source.token)

internal fun accountUsageCacheMatches(owner:String?,source:EnvironmentRequest?)=
    source!=null&&owner==accountUsageOwner(source)

internal fun accountUsageForToolbar(owner:String?,source:EnvironmentRequest?,active:EnvironmentRequest?,usage:WeeklyUsage):WeeklyUsage=
    if(source!=null&&active?.matches(source)==true&&accountUsageCacheMatches(owner,source))usage
    else WeeklyUsage(state="unavailable")

internal data class AccountToolbarFacts(val primary:String,val credits:String?,val freshness:String)

/** Formatting reuses the session account models, including zero and unlimited
 * credits. Absence is visible, not a hidden number or a fabricated zero.
 */
internal fun accountToolbarFacts(usage:WeeklyUsage,now:Long,connected:Boolean,locale:Locale=Locale.getDefault()):AccountToolbarFacts {
    val credits=usage.credits.compact(locale)
    val stale=usage.isStale(now,connected)||(credits!=null&&usage.credits.isStale(now,connected))
    return AccountToolbarFacts(usage.remainingPercent?.let{usage.remainingLabel(locale).removeSuffix(" left")}?:"—",credits,
        when{
            usage.remainingPercent==null&&credits==null->if(usage.state=="loading")"Loading…"else"Unavailable"
            stale->"Last known"
            else->""
        })
}
