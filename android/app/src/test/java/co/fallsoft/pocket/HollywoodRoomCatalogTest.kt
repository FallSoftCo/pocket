package co.fallsoft.pocket
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
class HollywoodRoomCatalogTest {
 @Test fun initialReadPublishesAtomicallyAndFailureRetainsNavigationWithoutStatus()=runBlocking {
  val release=CompletableDeferred<Unit>();var fail=false
  val id="00000000-0000-4000-8000-000000000111"
  val catalog=HollywoodRoomCatalog{release.await();if(fail)throw java.io.IOException("offline");JSONObject().put("rooms",JSONArray().put(JSONObject().put("id",id).put("name","Real room")))}
  val pending=async{catalog.refresh()};yield()
  assertTrue(catalog.loading);assertFalse(catalog.available);assertTrue(catalog.rooms.isEmpty())
  release.complete(Unit);pending.await();assertFalse(catalog.loading);assertTrue(catalog.available)
  fail=true;catalog.refresh();assertFalse(catalog.available);assertEquals(id,catalog.rooms.single().s("id"));assertEquals("offline",catalog.error)
  fail=false;catalog.refresh();assertTrue(catalog.available);assertEquals("",catalog.error)
 }
}
