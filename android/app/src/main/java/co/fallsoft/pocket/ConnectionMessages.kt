package co.fallsoft.pocket

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object ConnectionMessages {
    fun server(message:String)=when(message){
        "Codex disconnected","Codex is offline"->"Your phone reached the workstation, but Pocket lost its connection to Codex there. Pocket reconnects automatically. Reload the conversation; check it before resending a reply."
        else->message
    }
    fun error(e:Throwable):String {
        val causes=generateSequence(e){it.cause}.take(20).toList()
        return when {
            causes.any{it is UnknownHostException}->"Could not resolve your workstation’s name. Check that Tailscale is connected and using Tailscale DNS, then retry. Your pairing is saved."
            causes.any{it is SSLException}->"Pocket could not establish a secure HTTPS connection. Check the workstation’s HTTPS setup and the phone’s date and time, then retry."
            causes.any{it is ConnectException||it is NoRouteToHostException}->"Pocket cannot reach your workstation. Check that it is awake and Tailscale is connected on both devices, then retry."
            causes.any{it is SocketTimeoutException}->"Your workstation is taking too long to respond. Check its connection, then reload the conversation."
            else->server(e.message?:"Could not reach your workstation. Check Tailscale on both devices, then retry.")
        }
    }
}
