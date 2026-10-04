package co.fallsoft.pocket

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkstationDnsTest {
    private val host="workstation.example-tailnet.ts.net"
    private val offline=object:Dns{override fun lookup(hostname:String):List<InetAddress> = throw UnknownHostException("resolver unavailable")}
    @Test fun verifiedRouteSurvivesResolverFailureAndNewInstance(){
        val saved=mutableMapOf<String,String>()
        val dns=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        dns.rememberVerified(host,"100.64.12.34")
        val restarted=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        assertEquals("100.64.12.34",restarted.lookup(host).single().hostAddress)
    }
    @Test fun normalDnsAlwaysTakesPrecedence(){
        val fresh=InetAddress.getByAddress(byteArrayOf(100,80,1,2))
        val dns=WorkstationDns({host},{"100.64.12.34"},{_,_->},object:Dns{override fun lookup(hostname:String)=listOf(fresh)})
        assertEquals(fresh,dns.lookup(host).single())
    }
    @Test fun fallbackCannotApplyToOtherHostsOrUntrustedAddresses(){
        val dns=WorkstationDns({host},{"100.64.12.34"},{_,_->},offline)
        assertThrows(UnknownHostException::class.java){dns.lookup("other.example-tailnet.ts.net")}
        for(address in listOf("127.0.0.1","8.8.8.8","192.168.1.1","100.128.1.1","evil.example","100.64.0.999","::1","fe80::1")){
            assertNull(WorkstationDns.tailnetAddress(address))
            assertThrows(UnknownHostException::class.java){WorkstationDns({host},{address},{_,_->},offline).lookup(host)}
        }
    }
    @Test fun onlyVerifiedWorkstationTailnetAddressesCanBeRemembered(){
        val saved=mutableMapOf<String,String>();val dns=WorkstationDns({host},{saved[it]},{h,a->saved[h]=a},offline)
        dns.rememberVerified("other.example-tailnet.ts.net","100.64.12.34")
        dns.rememberVerified(host,"127.0.0.1");assertTrue(saved.isEmpty())
        assertThrows(UnknownHostException::class.java){dns.lookup(host)}
        dns.rememberVerified(host,"fd7a:115c:a1e0::1234")
        assertEquals(16,dns.lookup(host).single().address.size)
        assertThrows(UnknownHostException::class.java){WorkstationDns({"example.com"},{"100.64.12.34"},{_,_->},offline).lookup("example.com")}
    }
    @Test fun stalledFreshLookupFallsBackWithinDeadlineAndIsSingleFlight(){
        val release=CountDownLatch(1);val entered=CountDownLatch(1);val exited=CountDownLatch(1);val calls=AtomicInteger()
        val dns=WorkstationDns({host},{"100.64.12.34"},{_,_->},object:Dns{
            override fun lookup(hostname:String):List<InetAddress>{calls.incrementAndGet();entered.countDown();try{release.await();throw UnknownHostException("stalled")}finally{exited.countDown()}}
        },40)
        try{
            val started=System.nanoTime()
            assertEquals("100.64.12.34",dns.lookup(host).single().hostAddress)
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
            repeat(20){assertEquals("100.64.12.34",dns.lookup(host).single().hostAddress)}
            assertEquals(1,calls.get())
        }finally{release.countDown();assertTrue(exited.await(1,TimeUnit.SECONDS))}
    }
    @Test fun lateFreshResultWinsAfterAStalledLookupRecovers(){
        val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val fresh=InetAddress.getByAddress(byteArrayOf(100,80,1,2))
        val dns=WorkstationDns({host},{"100.64.12.34"},{_,_->},object:Dns{
            override fun lookup(hostname:String):List<InetAddress>{release.await();finished.countDown();return listOf(fresh)}
        },30)
        try{
            assertEquals("100.64.12.34",dns.lookup(host).single().hostAddress)
            release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            var result=dns.lookup(host).single()
            while(result!=fresh&&System.nanoTime()<deadline){Thread.yield();result=dns.lookup(host).single()}
            assertEquals(fresh,result)
        }finally{release.countDown()}
    }
    @Test fun coldLookupIsNotReplacedOrSilentlyLimitedByCachedDeadline(){
        val calls=AtomicInteger();val fresh=InetAddress.getByAddress(byteArrayOf(100,80,1,2))
        val dns=WorkstationDns({host},{null},{_,_->},object:Dns{
            override fun lookup(hostname:String):List<InetAddress>{calls.incrementAndGet();Thread.sleep(30);return listOf(fresh)}
        },1)
        assertEquals(fresh,dns.lookup(host).single());assertEquals(1,calls.get())
    }

    @Test fun sharedResolverBudgetBoundsWorkersAndWaitingLookups(){
        val release=CountDownLatch(1);val exited=CountDownLatch(4);val calls=AtomicInteger()
        val blocked=object:Dns{
            override fun lookup(hostname:String):List<InetAddress>{
                calls.incrementAndGet()
                try{release.await();throw UnknownHostException("stalled")}
                finally{exited.countDown()}
            }
        }
        try{
            val instances=(0 until 5).map{WorkstationDns({host},{"100.64.12.34"},{_,_->},blocked,30)}
            instances.forEach{assertEquals("100.64.12.34",it.lookup(host).single().hostAddress)}
            assertEquals(2,calls.get())
            repeat(20){instances.forEach{assertEquals("100.64.12.34",it.lookup(host).single().hostAddress)}}
            assertEquals(2,calls.get())
        }finally{release.countDown();assertTrue(exited.await(1,TimeUnit.SECONDS))}
    }

}
