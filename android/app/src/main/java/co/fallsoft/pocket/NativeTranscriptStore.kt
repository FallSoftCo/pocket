package co.fallsoft.pocket

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Native order is a rollout ordinal plus boundary rank and native identity.
 * No arrival time, translated text, title or viewport coordinate participates. */
internal data class NativeTranscriptKey(val position:Long,val rank:Int,val id:String):Comparable<NativeTranscriptKey>{
    override fun compareTo(other:NativeTranscriptKey):Int =
        position.compareTo(other.position).takeIf{it!=0}?:rank.compareTo(other.rank).takeIf{it!=0}?:id.compareTo(other.id)
    fun json()=JSONArray().put(position).put(rank).put(id)
    companion object{
        fun parse(value:JSONArray):NativeTranscriptKey{
            val number=value.opt(0) as? Number?:throw java.io.IOException("Invalid native timeline position")
            val p=number.toLong();val rank=value.getInt(1);val id=value.getString(2)
            if(p<0||number.toDouble()!=p.toDouble()||rank !in 0..3||id.isBlank())throw java.io.IOException("Invalid native timeline key")
            return NativeTranscriptKey(p,rank,id)
        }
    }
}

/** Sole durable transcript authority for native sources. Called only on the
 * repository's serialized storage lane. Legacy JSON caches are never imported
 * as ordering evidence. Commands/drafts/pairing are not stored here. */
internal class NativeTranscriptStore(context:android.content.Context,databaseName:String="codex-native-transcripts-v1.sqlite"){
    private val db=object:SQLiteOpenHelper(context,databaseName,null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE entry(scope TEXT,thread TEXT,id TEXT,pos INTEGER,rank INTEGER,sort_id TEXT,row_id TEXT,body TEXT,epoch TEXT,version INTEGER,bytes INTEGER,PRIMARY KEY(scope,thread,id))")
            db.execSQL("CREATE INDEX native_order ON entry(scope,thread,pos,rank,sort_id)")
            db.execSQL("CREATE INDEX native_row ON entry(scope,thread,row_id)")
            db.execSQL("CREATE TABLE page(scope TEXT,thread TEXT,id TEXT,request TEXT,next TEXT,first TEXT,last TEXT,PRIMARY KEY(scope,thread,id))")
            db.execSQL("CREATE TABLE state(scope TEXT,thread TEXT,detail TEXT,head TEXT,version INTEGER,notification INTEGER,revision INTEGER,bytes INTEGER,updated INTEGER,PRIMARY KEY(scope,thread))")
            db.execSQL("CREATE TABLE reader(scope TEXT,thread TEXT,body TEXT,PRIMARY KEY(scope,thread))")
        }
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    }.apply{setWriteAheadLoggingEnabled(true)}.writableDatabase
    private data class Page(val first:NativeTranscriptKey,val last:NativeTranscriptKey,val request:String?,val next:String?)
    private data class Component(var first:NativeTranscriptKey,var last:NativeTranscriptKey,var before:String?,val pages:MutableList<Page>)
    private data class View(var follow:Boolean=true,var anchor:String?=null,var low:NativeTranscriptKey?=null,var high:NativeTranscriptKey?=null,var revision:Long=0)
    private var viewSerial=0L
    private val views=linkedMapOf<Pair<String,String>,View>()
    private val details=linkedMapOf<Pair<String,String>,JSONObject>()
    private val coverage=mutableMapOf<Pair<String,String>,List<Component>>()
    // Read-only renderer objects are disposable projections of committed SQL.
    // Unchanged payloads keep stable identities without reparsing a whole window.
    private val rendered=linkedMapOf<String,Pair<String,JSONObject>>()
    private var renderedBytes=0L
    private fun render(scope:String,id:String,body:String):JSONObject{
        val key=scope+"/"+id+"/"+body.hashCode()
        rendered[key]?.takeIf{it.first==body}?.let{return it.second}
        val value=JSONObject(body);val weight=body.length*4L
        rendered.remove(key)?.let{renderedBytes-=it.first.length*4L}
        if(weight<=8L*1024*1024){
            rendered[key]=body to value;renderedBytes+=weight
            while(renderedBytes>8L*1024*1024&&rendered.isNotEmpty()){
                val oldest=rendered.keys.first();rendered.remove(oldest)?.let{renderedBytes-=it.first.length*4L}
            }
        }
        return value
    }
    fun releaseMemory(){rendered.clear();renderedBytes=0;coverage.clear();details.clear()}
    fun close(){db.close()}
    private fun args(scope:String,id:String)=arrayOf(scope,id)
    private fun state(scope:String,id:String)=db.rawQuery("SELECT detail,head,version,notification,revision,bytes FROM state WHERE scope=? AND thread=?",args(scope,id)).use{
        if(!it.moveToFirst())null else JSONObject().put("detail",JSONObject(it.getString(0))).put("head",JSONObject(it.getString(1)))
            .put("version",it.getLong(2)).put("notification",it.getLong(3)).put("revision",it.getLong(4)).put("bytes",it.getLong(5))
    }
    private fun view(scope:String,id:String):View=views.getOrPut(scope to id){
        val stored=reader(scope,id)
        View(stored?.follow?:true,stored?.rowId,revision=++viewSerial)
    }
    fun reader(scope:String,id:String):TranscriptReadPosition? =
        db.rawQuery("SELECT body FROM reader WHERE scope=? AND thread=?",args(scope,id)).use{c->
            if(!c.moveToFirst())null else JSONObject(c.getString(0)).let{TranscriptReadPosition(it.s("row"),it.optInt("offset"),it.optBoolean("follow",true))}
        }
    fun focus(scope:String,id:String,position:TranscriptReadPosition?){
        val v=view(scope,id);val following=position?.follow?:true
        if(v.follow!=following||v.anchor!=position?.rowId)v.revision=++viewSerial
        if(v.follow!=following){v.low=null;v.high=null}
        v.follow=following;v.anchor=position?.rowId
        if(position==null)db.delete("reader","scope=? AND thread=?",args(scope,id))
        else db.execSQL("INSERT OR REPLACE INTO reader VALUES(?,?,?)",
            arrayOf(scope,id,JSONObject().put("row",position.rowId).put("offset",position.offset).put("follow",position.follow).toString()))
    }
    private fun keyForRow(scope:String,id:String,row:String?):NativeTranscriptKey?{
        if(row==null)return null
        return db.rawQuery("SELECT pos,rank,sort_id FROM entry WHERE scope=? AND thread=? AND row_id=? LIMIT 1",arrayOf(scope,id,row)).use{
            if(it.moveToFirst())NativeTranscriptKey(it.getLong(0),it.getInt(1),it.getString(2))else null
        }
    }
    private fun components(scope:String,id:String):List<Component> = coverage.getOrPut(scope to id){
        val pages=mutableListOf<Page>();val ends=hashSetOf<String>()
        db.rawQuery("SELECT first,last,request,next FROM page WHERE scope=? AND thread=?",args(scope,id)).use{c->
            while(c.moveToNext()){
                if(c.isNull(0)){if(!c.isNull(2)&&c.isNull(3))ends.add(c.getString(2));continue}
                pages.add(Page(NativeTranscriptKey.parse(JSONArray(c.getString(0))),NativeTranscriptKey.parse(JSONArray(c.getString(1))),if(c.isNull(2))null else c.getString(2),if(c.isNull(3))null else c.getString(3)))
            }
        }
        val result=mutableListOf<Component>()
        for(p in pages.sortedBy{it.first}){
            val old=result.lastOrNull()
            val linked=old?.pages?.any{it.request!=null&&it.request==p.next||p.request!=null&&p.request==it.next}==true
            if(old!=null&&(p.first<=old.last||linked)){
                if(p.last>old.last)old.last=p.last
                old.pages.add(p)
            }else result.add(Component(p.first,p.last,p.next,mutableListOf(p)))
        }
        result.forEach{component->
            val first=component.pages.minBy{it.first}
            component.before=first.next?.takeUnless{it in ends}
        }
        result
    }
    fun commit(scope:String,id:String,value:JSONObject,cursor:String?){
        val page=value.getJSONObject("timeline")
        require(page.optInt("native")==1)
        val entries=page.getJSONArray("entries").objects()
        val keys=entries.map{NativeTranscriptKey.parse(it.getJSONArray("key"))}
        if(keys.zipWithNext().any{(a,b)->a>=b}||entries.map{it.s("id")}.toSet().size!=entries.size)throw java.io.IOException("Native timeline order changed")
        val head=value.optJSONObject("historyHead")?:JSONObject()
        val epoch=head.s("epoch");val version=value.optLong("revision")
        val safe=transcriptPresentationDetail(id,value)
        val notification=maxOf(value.optLong("_notificationObserved"),value.optJSONArray("notifications")?.objects()?.maxOfOrNull{it.optLong("id")}?:0)
        val next=page.s("before").takeIf{it.isNotBlank()}
        val pageId=java.security.MessageDigest.getInstance("SHA-256").digest(JSONArray().put(keys.firstOrNull()?.json()).put(keys.lastOrNull()?.json()).put(next).put(cursor).toString().toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        db.beginTransaction()
        try{
            var delta=0L
            for((i,entry)in entries.withIndex()){
                val key=keys[i];val row=entry.optJSONObject("row")?.let{JSONObject(it.toString()).put("_nativePosition",key.position).put("_nativeRank",key.rank).put("_nativeId",key.id).apply{if(s("kind")=="user")put("_inputOrder","canonical")}}
                val old=db.rawQuery("SELECT epoch,version,bytes FROM entry WHERE scope=? AND thread=? AND id=?",arrayOf(scope,id,entry.s("id"))).use{
                    if(it.moveToFirst())Triple(it.getString(0),it.getLong(1),it.getLong(2))else null
                }
                if(old!=null&&old.first==epoch&&old.second>version)continue
                val body=row?.toString();val bytes=body?.toByteArray(Charsets.UTF_8)?.size?.toLong()?:0
                db.execSQL("INSERT OR REPLACE INTO entry VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(scope,id,entry.s("id"),key.position,key.rank,key.id,row?.s("id"),body,epoch,version,bytes))
                delta+=bytes-(old?.third?:0)
            }
            db.execSQL("INSERT OR REPLACE INTO page VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(scope,id,pageId,cursor,next,keys.firstOrNull()?.json()?.toString(),keys.lastOrNull()?.json()?.toString()))
            val previous=state(scope,id)
            // Historical reads never replace live pending controls or current head.
            if(cursor==null||previous==null){
                val total=maxOf(0,(previous?.optLong("bytes")?:0)+delta)
                val keepLive=previous!=null&&previous.getJSONObject("head").s("epoch")==epoch&&previous.optLong("version")>version
                val storedHead=if(keepLive)previous!!.getJSONObject("head")else head
                val storedVersion=if(keepLive)previous!!.optLong("version")else version
                db.execSQL("INSERT OR REPLACE INTO state VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(scope,id,safe.toString(),storedHead.toString(),storedVersion,maxOf(notification,previous?.optLong("notification")?:0),(previous?.optLong("revision")?:0)+1,total,System.currentTimeMillis()))
            }else db.execSQL("UPDATE state SET bytes=MAX(0,bytes+?),revision=revision+1,updated=? WHERE scope=? AND thread=?",arrayOf<Any>(delta,System.currentTimeMillis(),scope,id))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        if(cursor==null)details[scope to id]=JSONObject(value.toString()).apply{remove("timeline")}
        coverage.remove(scope to id)
        while(details.size>32)details.remove(details.keys.first())
        while(views.size>32)views.remove(views.keys.first())
    }
    /** A live payload can replace a known canonical row, never create chronology. */
    fun live(scope:String,id:String,update:JSONObject):Boolean{
        val row=update.optJSONObject("row")?:return false
        val old=db.rawQuery("SELECT id,pos,rank,sort_id,body,epoch,version,bytes FROM entry WHERE scope=? AND thread=? AND row_id=?",arrayOf(scope,id,row.s("id"))).use{c->
            if(!c.moveToFirst())null else listOf(c.getString(0),c.getLong(1),c.getInt(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getLong(7))
        }?:return false
        val epoch=update.optJSONObject("historyHead")?.s("epoch").orEmpty()
        if(epoch!=old[5]||update.optLong("version")<(old[6] as Long))return false
        val previous=JSONObject(old[4] as String)
        val next=JSONObject(row.toString()).put("_nativePosition",old[1]).put("_nativeRank",old[2]).put("_nativeId",old[3])
        if(next.s("kind")=="user")next.put("_inputOrder","canonical")
        if(next.s("clientId").isBlank()&&previous.s("clientId").isNotBlank())next.put("clientId",previous.s("clientId"))
        val body=next.toString();val bytes=body.toByteArray().size.toLong()
        db.beginTransaction()
        try{
            db.execSQL("UPDATE entry SET body=?,version=?,bytes=? WHERE scope=? AND thread=? AND id=?",arrayOf<Any>(body,update.optLong("version"),bytes,scope,id,old[0] as String))
            db.execSQL("UPDATE state SET bytes=MAX(0,bytes+?),revision=revision+1,head=?,version=MAX(version,?) WHERE scope=? AND thread=?",arrayOf<Any>(bytes-(old[7] as Long),update.getJSONObject("historyHead").toString(),update.optLong("version"),scope,id))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return true
    }
    fun trim(protected:Set<Pair<String,String>>){
        var total=0L;var count=0
        val remove=mutableListOf<Pair<String,String>>()
        db.rawQuery("SELECT scope,thread,bytes FROM state ORDER BY updated DESC",null).use{c->
            while(c.moveToNext()){
                val key=c.getString(0) to c.getString(1);total+=c.getLong(2);count++
                if((total>64L*1024*1024||count>256)&&key !in protected)remove.add(key)
            }
        }
        if(remove.isEmpty())return
        db.beginTransaction()
        try{
            for((scope,id)in remove){
                for(table in listOf("entry","page","state"))db.delete(table,"scope=? AND thread=?",args(scope,id))
                coverage.remove(scope to id);details.remove(scope to id);views.remove(scope to id)
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    private fun local(direction:String,key:NativeTranscriptKey?=null)="native-local:"+direction+":"+(key?.json()?.toString().orEmpty())
    fun isLocal(cursor:String)=cursor.startsWith("native-local:")
    fun navigate(scope:String,id:String,cursor:String){
        require(isLocal(cursor))
        val v=view(scope,id);v.revision=++viewSerial;val direction=cursor.removePrefix("native-local:").substringBefore(':')
        if(direction=="latest"){v.follow=true;v.anchor=null;v.low=null;v.high=null;return}
        val key=NativeTranscriptKey.parse(JSONArray(cursor.substringAfter("native-local:"+direction+":")))
        v.follow=false
        v.anchor=db.rawQuery("SELECT row_id FROM entry WHERE scope=? AND thread=? AND (pos,rank,sort_id)=(?,?,?) AND body IS NOT NULL LIMIT 1",
            arrayOf(scope,id,key.position.toString(),key.rank.toString(),key.id)).use{if(it.moveToFirst())it.getString(0)else v.anchor}
        v.low=null;v.high=null
    }
    /** At most512 rendered rows are materialized. Older bodies remain indexed
     * locally and are paged without a network read or a tap-to-load requirement. */
    fun snapshot(scope:String,id:String):JSONObject?{
        val state=state(scope,id)?:return null
        val components=components(scope,id)
        val v=view(scope,id);val anchor=if(v.follow)null else keyForRow(scope,id,v.anchor)
        val component=components.lastOrNull{anchor!=null&&anchor>=it.first&&anchor<=it.last}?:components.lastOrNull()
        val output=JSONObject((details[scope to id]?:state.getJSONObject("detail")).toString())
        val rows=mutableListOf<JSONObject>()
        var before:String?=null;val gaps=JSONArray()
        if(component!=null){
            val bounds=mutableListOf<Any>(scope,id,component.first.position,component.first.rank,component.first.id,component.last.position,component.last.rank,component.last.id)
            var condition="scope=? AND thread=? AND (pos,rank,sort_id)>=(?,?,?) AND (pos,rank,sort_id)<=(?,?,?) AND body IS NOT NULL"
            val low=v.low?.takeIf{!v.follow&&it>=component.first&&it<=component.last}
            val high=v.high?.takeIf{!v.follow&&it>=component.first&&it<=component.last}
            // Explicit local paging extends from its native key. Normal historical
            // updates keep the reader's existing component and passage.
            if(low!=null){condition+=" AND (pos,rank,sort_id)<(?,?,?)";bounds.addAll(listOf(low.position,low.rank,low.id))}
            else if(high!=null){condition+=" AND (pos,rank,sort_id)>(?,?,?)";bounds.addAll(listOf(high.position,high.rank,high.id))}
            else if(anchor!=null){condition+=" AND (pos,rank,sort_id)<=(?,?,?)";bounds.addAll(listOf(anchor.position,anchor.rank,anchor.id))}
            val ascending=high!=null
            db.rawQuery("SELECT body FROM entry WHERE "+condition+" ORDER BY pos "+(if(ascending)"ASC"else"DESC")+",rank "+(if(ascending)"ASC"else"DESC")+",sort_id "+(if(ascending)"ASC"else"DESC")+" LIMIT 256",bounds.map{it.toString()}.toTypedArray()).use{c->while(c.moveToNext())rows.add(render(scope,id,c.getString(0)))}
            if(!ascending)rows.reverse()
            // An anchored passage needs its following context too.
            if(anchor!=null&&low==null&&high==null){
                db.rawQuery("SELECT body FROM entry WHERE scope=? AND thread=? AND body IS NOT NULL AND (pos,rank,sort_id)>(?,?,?) AND (pos,rank,sort_id)<=(?,?,?) ORDER BY pos,rank,sort_id LIMIT 128",
                    arrayOf(scope,id,anchor.position.toString(),anchor.rank.toString(),anchor.id,component.last.position.toString(),component.last.rank.toString(),component.last.id)).use{c->while(c.moveToNext())rows.add(render(scope,id,c.getString(0)))}
            }
            fun rowKey(row:JSONObject)=NativeTranscriptKey(row.getLong("_nativePosition"),row.getInt("_nativeRank"),row.s("_nativeId"))
            val first=rows.firstOrNull()?.let(::rowKey);val last=rows.lastOrNull()?.let(::rowKey)
            fun exists(op:String,key:NativeTranscriptKey)=db.rawQuery("SELECT 1 FROM entry WHERE scope=? AND thread=? AND body IS NOT NULL AND (pos,rank,sort_id)"+op+"(?,?,?) AND (pos,rank,sort_id)>=(?,?,?) AND (pos,rank,sort_id)<=(?,?,?) LIMIT 1",
                arrayOf(scope,id,key.position.toString(),key.rank.toString(),key.id,component.first.position.toString(),component.first.rank.toString(),component.first.id,component.last.position.toString(),component.last.rank.toString(),component.last.id)).use{it.moveToFirst()}
            before=if(first!=null&&exists("<",first))local("older",first)else component.before
            if(last!=null&&exists(">",last)){
                val gapId="native-newer";val cursor=local("newer",last)
                gaps.put(JSONObject().put("id",gapId).put("cursor",cursor).put("beforeRow","").put("olderAnchor",rows.last().s("id")))
                rows.add(JSONObject().put("id",TranscriptHistoryGap.ROW_PREFIX+gapId).put("kind","historyGap").put("historyDirection","newer"))
            }else if(component!==components.lastOrNull()){
                val gapId="native-latest";val cursor=local("latest")
                gaps.put(JSONObject().put("id",gapId).put("cursor",cursor).put("beforeRow","").put("olderAnchor",rows.lastOrNull()?.s("id").orEmpty()))
                rows.add(JSONObject().put("id",TranscriptHistoryGap.ROW_PREFIX+gapId).put("kind","historyGap").put("historyDirection","newer"))
            }
        }
        val page=JSONObject().put("native",1).put("rows",JSONArray(rows)).put("canonicalIds",JSONArray(rows.filter{it.s("kind")!="historyGap"}.map{it.s("id")})).put("hasEarlier",before!=null).put("before",before).put("gaps",gaps)
        val preview=db.rawQuery("SELECT body FROM entry WHERE scope=? AND thread=? AND body IS NOT NULL ORDER BY pos DESC,rank DESC,sort_id DESC LIMIT 16",args(scope,id)).use{c->
            var found:JSONObject?=null
            while(c.moveToNext()&&found==null){val row=render(scope,id,c.getString(0));if(row.s("kind") in listOf("message","user","activity")&&row.s("text").isNotBlank())found=row}
            found
        }
        val execution=db.rawQuery("SELECT body FROM entry WHERE scope=? AND thread=? AND rank IN (0,3) AND body IS NOT NULL ORDER BY pos DESC,rank DESC,sort_id DESC LIMIT 1",args(scope,id)).use{c->if(c.moveToFirst())render(scope,id,c.getString(0))else null}
        output.put("nativeExecutionTurn",execution)
        output.put("_nativeWindowBytes",rows.sumOf{it.s("text").length*2L+it.s("detail").length*2L+512})
        output.put("nativePreview",preview)
        return output.put("nativeViewRevision",v.revision).put("nativeWindow",JSONArray(rows.map{it.s("id")}).toString()).put("nativeFollowing",v.follow).put("windowSchema",3).put("timeline",page).put("historyHead",state.getJSONObject("head")).put("revision",state.optLong("version"))
            .put("nativeRevision",state.optLong("revision")).put("throughNotification",state.optLong("notification"))
            .put("readingPosition",reader(scope,id)?.let{JSONObject().put("row",it.rowId).put("offset",it.offset).put("follow",it.follow)})
    }
}
