/** Append-only stable final-response passages. No commentary/reasoning is audio.
 * Native item completion must agree with its streamed prefix; never correct
 * already-spoken content by silently replaying a replacement. */
export class CoordinatorSegments {
 constructor(db){this.db=db;db.exec(`CREATE TABLE IF NOT EXISTS coordinator_response_segments(device_id TEXT NOT NULL,turn_id TEXT NOT NULL,item_id TEXT NOT NULL,part INTEGER NOT NULL,text TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(device_id,turn_id,item_id,part));`);}
 list(device,turn){return this.db.prepare('SELECT item_id,part,text,created_at FROM coordinator_response_segments WHERE device_id=? AND turn_id=? ORDER BY created_at,rowid').all(device,turn).map(r=>({id:r.item_id+':'+r.part,text:r.text,at:r.created_at}));}
 event(active,m){
  const p=m.params||{};active.spokenItems??=new Map();
  if(m.method==='item/started'&&p.item?.type==='agentMessage')active.spokenItems.set(p.item.id,{phase:p.item.phase,text:'',through:0,part:0});
  const id=p.itemId||p.item?.id;if(!id)return;
  if(m.method==='item/agentMessage/delta'){
   const item=active.spokenItems.get(id);if(!item||item.phase!=='final_answer')return;
   item.text+=p.delta||'';this.commit(active,id,item,false);
  }
  if(m.method==='item/completed'&&p.item?.type==='agentMessage'&&p.item.phase!=='commentary'){
   const item=active.spokenItems.get(id)||{text:'',through:0,part:0};
   const complete=String(p.item.text||'');if(item.through&&!complete.startsWith(item.text.slice(0,item.through)))throw Error('Final response replaced an already committed spoken prefix.');
   item.text=complete;active.spokenItems.set(id,item);this.commit(active,id,item,true);
  }
 }
 commit(active,id,item,final){
  let rest=item.text.slice(item.through);
  while(rest.trim()){
   const match=rest.match(/^([\s\S]*?[.!?](?:["”’')\]]*)\s+)/);let length=match?.[0].length||0;
   if(!length&&rest.length>300){const boundary=rest.lastIndexOf(' ',280);if(boundary>=80)length=boundary+1;}
   if(!length&&final)length=rest.length;if(!length)break;
   const text=rest.slice(0,length).trim();item.through+=length;rest=item.text.slice(item.through);
   if(!text)continue;
   if(this.list(active.device,active.id).length>=48)throw Error('Response exceeded the bounded spoken passage limit; text is retained.');
   this.db.prepare('INSERT OR IGNORE INTO coordinator_response_segments VALUES(?,?,?,?,?,?)').run(active.device,active.id,id,item.part++,text,Date.now());
  }
 }
}
