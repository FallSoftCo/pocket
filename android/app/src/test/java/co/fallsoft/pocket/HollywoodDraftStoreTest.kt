package co.fallsoft.pocket

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HollywoodDraftStoreTest {
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun prefs(data:MutableMap<String,Any>):SharedPreferences {
        val editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)){proxy,method,args->when(method.name){
            "putString"->{data[args!![0] as String]=args[1];proxy};"remove"->{data.remove(args!![0]);proxy};"commit"->true;"apply"->null;else->proxy
        }} as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)){_,method,args->when(method.name){
            "edit"->editor;"getAll"->data.toMap();"contains"->data.containsKey(args!![0]);"getString"->data[args!![0]]?:args[1];else->null
        }} as SharedPreferences
    }
    private fun origin(node:Int)=EnvironmentRequest(EnvironmentIdentity("host$node","Host $node",fleet=FleetMembership(id(1),id(2),id(node),id(3))),"https://host$node.test","opaque-$node")
    private fun binding(node:Int)=JSONObject().put("bindingId",node.toString().padStart(64,'a')).put("installationId",id(6)).put("authorityId",id(8)).put("principalId",id(9))
    @Test fun roomBroadcastHasNoRecipientAndRetainsAddressedDraftAndUnknownSend() {
        val scope=HollywoodScope(id(10),"");val body=scope.message("room-command","Speak to the room")
        assertFalse(body.has("recipientId"));assertEquals(scope,HollywoodScope.from(scope.json()))
        try { HollywoodScope(id(10),"",audience="direct");fail("Private scope requires recipient") } catch(_:IllegalArgumentException) {}
        val data=mutableMapOf<String,Any>();val origin=origin(4);val store=HollywoodDraftStore(prefs(data),backendOwner(origin.endpoint,origin.token),binding(4),origin)
        val addressed=HollywoodScope(id(10),id(11));store.save(addressed,"Original addressed draft")
        store.save(scope,"New room draft")
        assertEquals("Original addressed draft",store.draft(addressed));assertEquals("New room draft",store.draft(scope))
        assertEquals(setOf(scope,addressed),store.scopes(scope.room).toSet())
    }
    @Test fun draftMovesAcrossGatewaysButClearingCannotResurrectLegacyTextOrCrossAnAudience() {
        val data=mutableMapOf<String,Any>();val preferences=prefs(data);val first=origin(4);val second=origin(5)
        val scope=HollywoodScope(id(10),id(11));val owner=backendOwner(first.endpoint,first.token)
        val physical="hollywood:$owner:${binding(4).s("bindingId")}:${scope.room}:${scope.recipient}:room:root:request"
        data["draft:$physical"]="Existing unsent work";data["scope:$physical"]=scope.json().toString()
        val a=HollywoodDraftStore(preferences,owner,binding(4),first)
        assertEquals("Existing unsent work",a.draft(scope))
        val b=HollywoodDraftStore(preferences,backendOwner(second.endpoint,second.token),binding(5),second)
        assertEquals("Existing unsent work",b.draft(scope));assertEquals(listOf(scope),b.scopes(scope.room))
        assertEquals("",b.draft(scope.copy(audience="direct")))
        b.save(scope,"Revised on another gateway");assertEquals("Revised on another gateway",a.draft(scope))
        b.save(scope,"");assertEquals("",a.draft(scope));assertEquals("Existing unsent work",data["draft:$physical"])
        val other=binding(5).put("principalId",id(12));assertEquals("",HollywoodDraftStore(preferences,"different owner",other,second).draft(scope))
    }
    @Test fun originalUnknownSendRemainsOnItsOriginalRouteAndIsDiscoverableWithoutReplayingIt() {
        val data=mutableMapOf<String,Any>();val preferences=prefs(data);val first=origin(4);val second=origin(5);val scope=HollywoodScope(id(10),id(11))
        val key="pending:hollywood:${backendOwner(first.endpoint,first.token)}:${binding(4).s("bindingId")}:${scope.room}:${scope.recipient}:room:root:request"
        val intent=JSONObject().put("binding",binding(4)).put("scope",scope.json()).put("state","unknown").put("body",scope.message("original-command","Do this once"))
        data[key]=intent.toString();val before=data.toMap()
        val saved=hollywoodOtherSends(preferences,second,binding(5),scope.room,listOf(first,second)).single()
        assertTrue(saved.origin.matches(first));assertEquals(scope,saved.scope);assertEquals(before,data)
        assertTrue(hollywoodOtherSends(preferences,second,binding(5).put("principalId",id(12)),scope.room,listOf(first,second)).isEmpty())
        assertEquals(before,data)
    }
    @Test fun distinctLocalHumansSharingCatalogViewerKeepBothDraftsAndOriginalUnknownReceipt() {
        val data=mutableMapOf<String,Any>();val preferences=prefs(data)
        val first=origin(4);val second=origin(5);val a=binding(4);val b=binding(5).put("principalId",id(12))
        val scope=HollywoodScope(id(10),"")
        val aStore=HollywoodDraftStore(preferences,backendOwner(first.endpoint,first.token),a,first)
        val bStore=HollywoodDraftStore(preferences,backendOwner(second.endpoint,second.token),b,second)
        aStore.save(scope,"First original draft");bStore.save(scope,"Second original draft")
        val pending="pending:hollywood:${backendOwner(first.endpoint,first.token)}:${a.s("bindingId")}:${scope.room}::room:root:request"
        data[pending]=JSONObject().put("binding",a).put("scope",scope.json()).put("state","unknown").toString()
        fun row(origin:EnvironmentRequest,binding:JSONObject,viewer:String):WorkSourceSnapshot<WorkCatalogPayload> {
            val key=WorkSourceKey(origin.environment.id,"owner", "lax")
            val value=WorkCatalogPayload(origin,"{}",binding.toString(),HollywoodCatalogViewer(id(8),viewer))
            return WorkSourceSnapshot(key,1,listOf(WorkEntry(WorkItemKey(key,WorkKind.ROOM,id(8),scope.room),0,value)))
        }
        val catalog=listOf(row(first,a,"a".repeat(64)),row(second,b,"a".repeat(64)));val before=data.toMap()
        val saved=hollywoodOtherSends(preferences,second,b,scope.room,listOf(first,second),catalog).single()
        assertTrue(saved.origin.matches(first));assertEquals(a.s("bindingId"),saved.binding.s("bindingId"))
        val draft=hollywoodOtherDrafts(preferences,second,b,scope.room,catalog).single()
        assertTrue(draft.origin.matches(first));assertEquals(scope,draft.scope)
        assertEquals("First original draft",aStore.draft(scope));assertEquals("Second original draft",bStore.draft(scope))
        assertEquals(before,data)
        val foreign=listOf(row(first,a,"b".repeat(64)),catalog[1])
        assertTrue(hollywoodOtherSends(preferences,second,b,scope.room,listOf(first,second),foreign).isEmpty())
        assertTrue(hollywoodOtherDrafts(preferences,second,b,scope.room,foreign).isEmpty())
    }

}
