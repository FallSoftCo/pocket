package co.fallsoft.pocket

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A DNS fallback for exactly the configured workstation; HTTPS still authenticates the hostname. */
class WorkstationDns(
    private val workstation:()->String?,
    private val read:(String)->String?,
    private val write:(String,String)->Unit,
    private val system:Dns=Dns.SYSTEM,
    private val freshLookupWaitMs:Long=1200
):Dns {
    private fun eligible(host:String)=host==workstation()&&host.endsWith(".ts.net")
    private data class Pending(val future:Future<List<InetAddress>>,val startedNanos:Long)
    private val pending=mutableMapOf<String,Pending>()
    private fun normalLookup(host:String)=system.lookup(host).also{if(it.isEmpty())throw UnknownHostException(host)}
    override fun lookup(hostname:String):List<InetAddress> {
        val cached=if(eligible(hostname))tailnetAddress(read(hostname))else null
        // Cold and unrelated lookups retain the platform resolver's normal behavior.
        if(cached==null)return normalLookup(hostname)
        val lookup=synchronized(pending){
            pending.entries.removeAll{it.key!=hostname&&it.value.future.isDone}
            pending[hostname]?:try{
                Pending(resolver.submit<List<InetAddress>>{normalLookup(hostname)},System.nanoTime()).also{pending[hostname]=it}
            }catch(_:RejectedExecutionException){null}
        }
        fun fallback():List<InetAddress>{
            if(!eligible(hostname))throw UnknownHostException(hostname)
            return listOf(cached)
        }
        if(lookup==null)return fallback()
        try {
            val remaining=TimeUnit.MILLISECONDS.toNanos(freshLookupWaitMs)-(System.nanoTime()-lookup.startedNanos)
            if(!lookup.future.isDone&&remaining<=0)return fallback()
            return lookup.future.get(remaining.coerceAtLeast(0),TimeUnit.NANOSECONDS)
        }catch(_:TimeoutException){return fallback()}
        catch(failure:ExecutionException){
            if(failure.cause is UnknownHostException)return fallback()
            throw UnknownHostException(hostname).apply{initCause(failure.cause)}
        }catch(failure:InterruptedException){
            Thread.currentThread().interrupt()
            throw UnknownHostException(hostname).apply{initCause(failure)}
        }finally{
            // Do not cancel a timed-out native lookup: late fresh results still take precedence.
            if(lookup.future.isDone)synchronized(pending){if(pending[hostname]===lookup)pending.remove(hostname)}
        }
    }
    // Call only after OkHttp has authenticated an HTTPS connection to this host.
    fun rememberVerified(host:String,address:String){
        if(eligible(host))tailnetAddress(address)?.hostAddress?.let{write(host,it)}
    }
    companion object {
        // Native DNS may ignore interruption. Bound both process-wide workers and waiting lookups;
        // repeated calls share their existing lookup, even after its foreground deadline expires.
        private val resolver=ThreadPoolExecutor(2,2,60,TimeUnit.SECONDS,ArrayBlockingQueue(2),{task->
            Thread(task,"NextComp verified-route DNS").apply{isDaemon=true}
        },ThreadPoolExecutor.AbortPolicy()).apply{allowCoreThreadTimeOut(true)}
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
