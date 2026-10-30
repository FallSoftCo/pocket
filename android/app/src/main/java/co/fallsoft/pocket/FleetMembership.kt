package co.fallsoft.pocket

import org.json.JSONObject

/** Persisted only after an authenticated fleet exchange. Contains no bearer token. */
data class FleetMembership(
    val fleetId:String,val accountId:String,val nodeId:String,val ownerDeviceId:String
) {
    init { listOf(fleetId,accountId,nodeId,ownerDeviceId).forEach{FleetDescriptor.identity(it)} }
    fun json()=JSONObject().put("fleetId",fleetId).put("accountId",accountId)
        .put("nodeId",nodeId).put("ownerDeviceId",ownerDeviceId)
    override fun toString()="FleetMembership(private owner)"
    companion object {
        fun read(row:JSONObject)=FleetMembership(row.getString("fleetId"),row.getString("accountId"),
            row.getString("nodeId"),row.getString("ownerDeviceId"))
    }
}

internal class FleetEnrollment private constructor(
    val membership:FleetMembership,val deviceId:String,val token:String
) {
    override fun toString()="FleetEnrollment(credentials redacted)"
    companion object {
        fun read(row:JSONObject,fleet:FleetDescriptor,node:FleetMember,ownerDeviceId:String):FleetEnrollment {
            require(row.opt("version")==1&&row.opt("fleetId")==fleet.id&&
                row.opt("accountId")==fleet.accountId&&row.opt("nodeId")==node.id&&
                row.opt("ownerDeviceId")==ownerDeviceId){"Fleet enrollment audience changed"}
            val local=FleetDescriptor.identity(row.getString("deviceId"))
            val token=row.opt("token") as? String?:error("Fleet enrollment unavailable")
            require(token.length in 32..16384&&!token.any{it.isWhitespace()})
            return FleetEnrollment(FleetMembership(fleet.id,fleet.accountId,node.id,ownerDeviceId),local,token)
        }
    }
}
