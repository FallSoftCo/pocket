package co.fallsoft.pocket

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object PocketNetwork {
    private val dns=WorkstationDns(
        workstation={Pocket.base.toHttpUrlOrNull()?.host},
        read={host->Pocket.prefs.getString("verified-route:$host",null)},
        write={host,address->Pocket.prefs.edit().putString("verified-route:$host",address).apply()}
    )
    fun client():OkHttpClient=OkHttpClient.Builder().dns(dns)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS)
        .eventListener(object:EventListener(){
            override fun connectionAcquired(call:Call,connection:Connection){
                val host=call.request().url.host
                if(call.request().url.isHttps&&connection.handshake()!=null&&connection.route().address.url.host==host){
                    connection.route().socketAddress.address?.hostAddress?.let{dns.rememberVerified(host,it)}
                }
            }
        }).build()
    fun error(e:Throwable):String {
        if(generateSequence(e){it.cause}.any{it is UnknownHostException})return "Could not resolve your workstation’s name. Check that Tailscale is connected and using Tailscale DNS, then retry. Your pairing is saved."
        return e.message?:"Could not reach your workstation"
    }
}
