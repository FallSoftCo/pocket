package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionMessagesTest {
    @Test fun rawDrainingErrorExplainsRestartAndSafeRetry(){
        val message=ConnectionMessages.server("Server is draining; retry after reconnecting")
        assertEquals(ConnectionMessages.draining,message)
        assertTrue(message.contains("existing work finish"))
        assertTrue(message.contains("reconnects automatically"))
        assertTrue(message.contains("before resending a reply"))
        assertEquals(message,ConnectionMessages.error(Exception("Server is draining; retry after reconnecting")))
    }
    @Test fun oldBackendErrorsIdentifyWhichConnectionFailed(){
        val message=ConnectionMessages.error(Exception("Codex disconnected"))
        assertTrue(message.contains("phone reached the workstation"))
        assertTrue(message.contains("resending a reply"))
        assertEquals("Task was archived",ConnectionMessages.error(Exception("Task was archived")))
    }
    @Test fun phoneDnsReachabilityAndTlsFailuresHaveDifferentActions(){
        assertTrue(ConnectionMessages.error(Exception("request failed",UnknownHostException())).contains("Tailscale DNS"))
        assertTrue(ConnectionMessages.error(ConnectException()).contains("awake"))
        assertTrue(ConnectionMessages.error(SSLHandshakeException("invalid certificate")).contains("HTTPS setup"))
    }
}
