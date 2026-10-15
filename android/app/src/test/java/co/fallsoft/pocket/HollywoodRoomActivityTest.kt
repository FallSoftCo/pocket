package co.fallsoft.pocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HollywoodRoomActivityTest {
 private fun room()=JSONObject().put("activity",JSONObject().put("version",1).put("observedAt",1770000000000L).apply{HollywoodRoomActivity.keys.forEach{put(it,0)}})
 @Test fun mixedDeliveryAndOverlappingAttentionCountsStaySeparate(){
  val r=room();r.getJSONObject("activity").put("pending",3).put("running",2).put("waitingForHuman",2).put("unknown",1)
  val a=HollywoodRoomActivity.read(r,true)!!
  assertFalse(a.idle);assertTrue(a.executing);assertTrue(a.needsHuman);assertTrue(a.uncertain)
  assertEquals(listOf("Queued" to 3L,"Running" to 2L,"Unknown" to 1L,"Needs you" to 2L),a.categories())
 }
 @Test fun onlyCompleteValidSnapshotCanAssertIdle(){
  assertTrue(HollywoodRoomActivity.read(room(),true)!!.idle)
  assertNull(HollywoodRoomActivity.read(room(),false))
  assertNull(HollywoodRoomActivity.read(JSONObject(),true))
  for(key in listOf("version","observedAt")+HollywoodRoomActivity.keys){
   val missing=room();missing.getJSONObject("activity").remove(key);assertNull(key,HollywoodRoomActivity.read(missing,true))
   for(value in listOf(-1,1.25,"1",JSONObject.NULL,9007199254740992L)){
    val invalid=room();invalid.getJSONObject("activity").put(key,value);assertNull("$key=$value",HollywoodRoomActivity.read(invalid,true))
   }
  }
  val future=room();future.getJSONObject("activity").put("version",2);assertNull(HollywoodRoomActivity.read(future,true))
 }
}
