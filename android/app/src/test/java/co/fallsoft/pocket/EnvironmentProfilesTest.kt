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
}
