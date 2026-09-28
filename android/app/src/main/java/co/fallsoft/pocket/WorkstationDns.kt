package co.fallsoft.pocket

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** A DNS fallback for exactly the configured workstation; HTTPS still authenticates the hostname. */
class WorkstationDns(
    private val workstation:()->String?,
    private val read:(String)->String?,
    private val write:(String,String)->Unit,
    private val system:Dns=Dns.SYSTEM
):Dns {
    private fun eligible(host:String)=host==workstation()&&host.endsWith(".ts.net")
    override fun lookup(hostname:String):List<InetAddress> {
        try{return system.lookup(hostname).also{if(it.isEmpty())throw UnknownHostException(hostname)}}
        catch(failure:UnknownHostException){
            if(eligible(hostname))tailnetAddress(read(hostname))?.let{return listOf(it)}
            throw failure
        }
    }
    // Call only after OkHttp has authenticated an HTTPS connection to this host.
    fun rememberVerified(host:String,address:String){
        if(eligible(host))tailnetAddress(address)?.hostAddress?.let{write(host,it)}
    }
    companion object {
        fun tailnetAddress(value:String?):InetAddress? {
            if(value==null)return null
            if(value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))){
                val parts=value.split('.').map{it.toInt()}
                if(parts.any{it !in 0..255}||parts[0]!=100||parts[1] !in 64..127)return null
                return InetAddress.getByAddress(parts.map{it.toByte()}.toByteArray())
            }
            // Numeric IPv6 only: never recursively resolve another hostname from preferences.
            if(value.contains(':')&&value.matches(Regex("[0-9a-fA-F:]+"))){
                val address=try{InetAddress.getByName(value)}catch(_:UnknownHostException){return null}
                val b=address.address
                if(b.size==16&&b.take(6)==listOf(0xfd,0x7a,0x11,0x5c,0xa1,0xe0).map{it.toByte()})return address
            }
            return null
        }
    }
}
