package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One bound catalog read publishes atomically; failed refreshes retain navigation, not status assertions. */
@Stable internal class HollywoodRoomCatalog(private val read:suspend ()->JSONObject) {
    var rooms by mutableStateOf(emptyList<JSONObject>());private set
    var loading by mutableStateOf(true);private set
    var available by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    private val mutex=Mutex()
    suspend fun refresh()=mutex.withLock {
        loading=true
        try {
            val next=read().getJSONArray("rooms").objects()
            require(next.all{hollywoodUuid(it.s("id"))&&it.s("name").isNotBlank()}&&next.map{it.s("id")}.distinct().size==next.size){"Room catalog could not be verified"}
            rooms=next;available=true;error=""
        }catch(e:CancellationException){throw e}
        catch(e:Exception){available=false;error=e.message?:"Rooms could not refresh"}
        finally{loading=false}
    }
}
