package co.fallsoft.pocket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reuse the protected, already-paired, nonexecuting QA authority; never mint or borrow a pairing. */
internal fun prepareRealRoomQaEnvironment(context:Context,config:JSONObject):String {
 require(config.s("base")=="http://127.0.0.1:18890")
 require(config.getJSONObject("identity").s("authorityId")=="6b8d1e3c-12c0-4c6d-8943-a7fdb32deece")
 require(config.s("roomId")=="6d966d5c-6d16-4fa9-91ee-5313e86312f7")
 val id="live_room_qa_20261015";val prefs=context.getSharedPreferences("pocket",0)
 val profiles=JSONArray(prefs.getString("environments:v1","[]")).objects().filter{it.s("id")!=id}
 val owner=backendOwner(config.s("base"),config.s("token"))
 check(prefs.edit().putString("environments:v1",JSONArray(profiles+JSONObject().put("id",id).put("name","Real room QA").put("local",false)).toString())
  .putString("activeEnvironment",id).putString("environment:$id:server",config.s("base")).putString("environment:$id:token",config.s("token"))
  .putString("backend:"+owner,"lax").putString("hollywood-room:"+owner+":"+config.getJSONObject("identity").s("bindingId"),config.s("roomId")).commit())
 return id
}
