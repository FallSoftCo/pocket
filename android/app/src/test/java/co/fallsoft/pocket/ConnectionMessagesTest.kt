package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionMessagesTest {
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
