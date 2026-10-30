package co.fallsoft.pocket

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** An authenticated fleet descriptor is metadata, never a bag of peer passwords. */
internal data class FleetMember(val id:String,val name:String,val origin:String)
internal class FleetDescriptor private constructor(
    val id:String,val accountId:String,val nodeId:String,val members:List<FleetMember>,val enrollmentPath:String?
) {
    override fun toString()="FleetDescriptor(${members.size} members)"
    companion object {
        private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        internal fun identity(value:String)=value.takeIf{uuid.matches(it)}?:error("Invalid fleet identity")
        internal fun origin(value:String):String {
            val uri=URI(value)
            require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.rawUserInfo==null&&
                uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath.orEmpty() in setOf("","/")&&
                uri.host==uri.host.lowercase()&&uri.port in -1..65535&&uri.port!=0&&uri.port!=443&&
                uri.normalize()==uri&&!uri.rawPath.orEmpty().contains('%')){"Invalid fleet origin"}
            return value.trimEnd('/')
        }
        fun read(value:JSONObject):FleetDescriptor {
            require(value.opt("version")==1){"Unsupported fleet descriptor"}
            fun string(row:JSONObject,key:String)=row.opt(key) as? String?:error("Missing fleet metadata")
            val id=identity(string(value,"id"));val account=identity(string(value,"accountId"))
            val node=identity(string(value,"nodeId"));require(value.opt("sessionPath")=="/api/fleet/session")
            val raw=value.getJSONArray("members");require(raw.length() in 1..128)
            val members=(0 until raw.length()).map{index->
                val row=raw.getJSONObject(index);val name=string(row,"name")
                require(name.isNotBlank()&&name.length<=100&&!name.any{it.isISOControl()})
                FleetMember(identity(string(row,"id")),name,origin(string(row,"origin")))
            }
            require(members.map{it.id}.distinct().size==members.size&&
                members.map{it.origin}.distinct().size==members.size&&members.any{it.id==node})
            val enrollment=value.opt("enrollmentPath").takeUnless{it==JSONObject.NULL}
            require(enrollment==null||enrollment=="/api/fleet/enroll")
            return FleetDescriptor(id,account,node,members,enrollment as? String)
        }
    }
}

/** Logical ownership survives access-token renewal and gateway changes. */
internal data class FleetWorkIdentity(
    val fleetId:String,val accountId:String,val viewerId:String,val kind:WorkKind,
    val namespace:String,val id:String
) {
    init {
        FleetDescriptor.identity(fleetId);FleetDescriptor.identity(accountId)
        require(viewerId.isNotBlank()&&viewerId.length<=200&&!viewerId.contains('\u0000'))
        // Rooms name their authority; sessions name their execution node.
        FleetDescriptor.identity(namespace)
        require(id.isNotBlank()&&id.length<=200&&!id.contains('\u0000'))
    }
    val key:String get()=MessageDigest.getInstance("SHA-256").digest(
        listOf(fleetId,accountId,viewerId,kind.name,namespace,id).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    ).joinToString(""){"%02x".format(it)}
    override fun toString()="FleetWorkIdentity(${kind.name}, private owner)"
}

internal class FleetSession private constructor(
    val fleetId:String,val accountId:String,val deviceId:String,val token:String,val expiresAt:Long
) {
    override fun toString()="FleetSession(credentials redacted)"
    companion object {
        fun read(value:JSONObject,descriptor:FleetDescriptor,now:Long):FleetSession {
            require(value.opt("version")==1)
            val fleet=value.opt("fleetId") as? String;val account=value.opt("accountId") as? String
            require(fleet==descriptor.id&&account==descriptor.accountId){"Fleet login audience changed"}
            val device=FleetDescriptor.identity(value.getString("deviceId"))
            val token=value.opt("token") as? String?:error("Fleet login unavailable")
            val expires=value.opt("expiresAt")
            require((expires is Int||expires is Long)&&token.length in 32..16384&&!token.any{it.isWhitespace()})
            val deadline=(expires as Number).toLong();require(deadline>now&&deadline-now<=86_400_000)
            return FleetSession(fleet!!,account!!,device,token,deadline)
        }
    }
}
