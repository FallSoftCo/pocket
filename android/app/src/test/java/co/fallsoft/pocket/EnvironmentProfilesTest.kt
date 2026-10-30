package co.fallsoft.pocket
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class EnvironmentProfilesTest {
 private fun prefs(data:MutableMap<String,Any>):SharedPreferences {
  val editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)){proxy,method,args->when(method.name){
   "putString","putBoolean","putLong","putInt"->{data[args!![0] as String]=args[1];proxy}
   "remove"->{data.remove(args!![0]);proxy};"commit"->true;"apply"->null;else->proxy
  }} as SharedPreferences.Editor
  return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)){_,method,args->when(method.name){
   "edit"->editor;"getAll"->data.toMap();"contains"->data.containsKey(args!![0]);"getString","getBoolean","getLong","getInt"->data[args!![0]]?:args[1];else->null
  }} as SharedPreferences
 }
 @Test fun pairingRemotePreservesOriginalAndPhoneAndDrafts(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private","draft:same" to "original draft","local:server" to "http://127.0.0.1:18880","local:token" to "phone-private","outbox:pending" to "unknown retained")
  val store=EnvironmentProfiles(prefs(values));val next=store.add("OZZZ","https://new.example","new-private","device-new")
  assertEquals(3,store.all().size);assertEquals("old-private",values["token"]);assertEquals("phone-private",values["local:token"])
  assertTrue(store.select(next.id));assertEquals(next.id,EnvironmentProfiles(prefs(values)).selected().id)
  assertEquals("original draft",values["draft:same"]);assertEquals("unknown retained",values["outbox:pending"])
  assertEquals("new-private",store.capture(next.id)?.token);assertEquals("old-private",store.capture("workstation")?.token)
 }
 @Test fun phoneReturnsToLastRemoteWithoutReplacingLegacy(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private","local:server" to "http://127.0.0.1:18880","local:token" to "phone-private")
  val store=EnvironmentProfiles(prefs(values));val next=store.add("OZZZ","https://new.example","new-private","new-device");store.select(next.id);store.select("phone")
  assertEquals("phone",store.selected().id);assertEquals(next.id,EnvironmentProfiles(prefs(values)).lastRemote().id);assertEquals("old-private",store.capture("workstation")?.token)
 }
 @Test fun unknownSelectionAndFailedPairCannotOverwriteActive(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private");val store=EnvironmentProfiles(prefs(values))
  assertFalse(store.select("missing"));try{store.add("Bad","http://untrusted.example","bad","bad");fail()}catch(_:IllegalArgumentException){}
  assertEquals("old-private",values["token"]);assertFalse(values.containsKey("activeEnvironment"))
 }
 @Test fun repairingSameOriginRetainsIdentityAndOldUncertainReceipts(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private","newTaskRequest" to "uncertain")
  val store=EnvironmentProfiles(prefs(values));val paired=store.add("Workstation","https://old.example","rotated-private","device-rotated")
  assertEquals("workstation",paired.id);assertEquals("uncertain",values["newTaskRequest"]);assertEquals("rotated-private",store.capture("workstation")?.token)
 }

 @Test fun inactivePushCapturesItsOwnPairingWithoutChangingConversationState(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private","deviceId" to "old-device","draft:same" to "Keep typing","outbox:unknown" to "Uncertain receipt")
  val store=EnvironmentProfiles(prefs(values));val remote=store.add("OZZZ","https://ozzz.example","new-private","new-device")
  val before=values.toMap();val origin=requireNotNull(store.capturePush("new-device"))
  assertEquals(remote.id,origin.environment.id);assertEquals("https://ozzz.example",origin.endpoint)
  assertEquals("new-private",origin.token);assertEquals("workstation",store.selected().id)
  assertEquals(before,values)
  store.select("workstation");assertEquals(origin,store.capturePush("new-device"))
 }
 @Test fun unknownMissingRevokedAndAmbiguousPushOriginsNeverFallBackToSelectedHost(){
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private","deviceId" to "old-device")
  val store=EnvironmentProfiles(prefs(values));val remote=store.add("OZZZ","https://ozzz.example","new-private","new-device")
  assertNull(store.capturePush(""));assertNull(store.capturePush("unknown-device"))
  values.remove(remote.key("token"));assertNull(store.capturePush("new-device"))
  values[remote.key("token")]="new-private";values[remote.key("deviceId")]="old-device"
  assertNull(store.capturePush("old-device"));assertEquals("workstation",store.selected().id)
 }

 @Test fun automaticFleetEnrollmentPreservesLegacyCredentialsDraftsAndUnknownReceipts(){
  fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
  val values=mutableMapOf<String,Any>("server" to "https://old.example","token" to "old-private",
   "deviceId" to "original-device","draft:same" to "Keep typing","outbox:unknown" to "Unknown original send")
  val original=values.toMap();val store=EnvironmentProfiles(prefs(values))
  val raw=org.json.JSONObject().put("version",1).put("id",id(1)).put("accountId",id(2)).put("nodeId",id(3))
   .put("sessionPath","/api/fleet/session").put("members",org.json.JSONArray()
    .put(org.json.JSONObject().put("id",id(3)).put("name","Original").put("origin","https://old.example"))
    .put(org.json.JSONObject().put("id",id(4)).put("name","GPU").put("origin","https://gpu.example.test")))
  val fleet=FleetDescriptor.read(raw)
  fun access(d:FleetDescriptor,m:FleetMember,token:String)=FleetEnrollment.read(org.json.JSONObject()
   .put("version",1).put("fleetId",d.id).put("accountId",d.accountId).put("nodeId",m.id)
   .put("deviceId",id(5)).put("ownerDeviceId",id(6)).put("token",token),d,m,id(6))
  val anchor=store.enroll(fleet,fleet.members[0],access(fleet,fleet.members[0],"a".repeat(64)))
  assertEquals("workstation",anchor.id)
  val remote=store.enroll(fleet,fleet.members[1],access(fleet,fleet.members[1],"b".repeat(64)))
  for((key,value) in original)assertEquals(value,values[key])
  assertEquals("workstation",store.selected().id)
  assertEquals("b".repeat(64),store.capture(remote.id)?.token)
  val restored=EnvironmentProfiles(prefs(values));assertEquals(id(4),restored.capture(remote.id)?.environment?.fleet?.nodeId)
  store.add("Third","https://third.example.test","third-private","third-device")
  assertEquals(remote.fleet,store.capture(remote.id)?.environment?.fleet)
  store.enroll(fleet,fleet.members[1],access(fleet,fleet.members[1],"c".repeat(64)))
  assertEquals("b".repeat(64),store.capture(remote.id)?.token)
  val changed=FleetDescriptor.read(org.json.JSONObject(raw.toString()).put("accountId",id(9)))
  val before=values.toMap()
  assertTrue(runCatching{store.enroll(changed,changed.members[1],access(changed,changed.members[1],"d".repeat(64)))}.isFailure)
  assertEquals(before,values)
 }
}
