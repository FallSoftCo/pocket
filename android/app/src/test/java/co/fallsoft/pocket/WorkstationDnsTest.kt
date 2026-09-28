package co.fallsoft.pocket

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class WorkstationDnsTest {
    private val host="workstation.example-tailnet.ts.net"
    private val offline=object:Dns{override fun lookup(hostname:String):List<InetAddress> = throw UnknownHostException("resolver unavailable")}
    @Test fun verifiedRouteSurvivesResolverFailureAndNewInstance(){
        val saved=mutableMapOf<String,String>()
        val dns=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        dns.rememberVerified(host,"100.79.169.20")
        val restarted=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        assertEquals("100.79.169.20",restarted.lookup(host).single().hostAddress)
    }
    @Test fun normalDnsAlwaysTakesPrecedence(){
        val fresh=InetAddress.getByAddress(byteArrayOf(100,80,1,2))
        val dns=WorkstationDns({host},{"100.79.169.20"},{_,_->},object:Dns{override fun lookup(hostname:String)=listOf(fresh)})
        assertEquals(fresh,dns.lookup(host).single())
    }
    @Test fun fallbackCannotApplyToOtherHostsOrUntrustedAddresses(){
        val dns=WorkstationDns({host},{"100.79.169.20"},{_,_->},offline)
        assertThrows(UnknownHostException::class.java){dns.lookup("other.example-tailnet.ts.net")}
        for(address in listOf("127.0.0.1","8.8.8.8","192.168.1.1","100.128.1.1","evil.example","100.64.0.999","::1","fe80::1")){
            assertNull(WorkstationDns.tailnetAddress(address))
            assertThrows(UnknownHostException::class.java){WorkstationDns({host},{address},{_,_->},offline).lookup(host)}
        }
    }
    @Test fun onlyVerifiedWorkstationTailnetAddressesCanBeRemembered(){
        val saved=mutableMapOf<String,String>();val dns=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        dns.rememberVerified("other.example-tailnet.ts.net","100.79.169.20")
        dns.rememberVerified(host,"127.0.0.1");assertTrue(saved.isEmpty())
        assertThrows(UnknownHostException::class.java){dns.lookup(host)}
        dns.rememberVerified(host,"fd7a:115c:a1e0::1234")
        assertEquals(16,dns.lookup(host).single().address.size)
        assertThrows(UnknownHostException::class.java){WorkstationDns({"example.com"},{"100.79.169.20"},{_,_->},offline).lookup("example.com")}
    }
}
