const fail=(message,status=400)=>Object.assign(Error(message),{status});
function excerpt(text){
 const clean=String(text||'').replace(/```[\s\S]*?```/g,' Code is available in the session. ').replace(/!\[[^\]]*\]\([^)]*\)/g,'').replace(/\[([^\]]+)\]\([^)]*\)/g,'$1').replace(/[\r\n]+/g,' ').replace(/[*#`]/g,'').replace(/\s+/g,' ').trim();
 if(clean.length<=220)return clean;
 const boundary=clean.slice(0,220).match(/^([\s\S]*[.!?])(?:\s|$)/)?.[1];return boundary||clean.slice(0,180).replace(/\s+\S*$/,'')+'… More is available in the session.';
}
/** A durable listening window, not a scheduled model or a transcript player.
 * Fetch/offered != heard. Earlier catch-up is deliberately not auto-enrolled. */
export class CoordinatorListening {
 constructor({db,catchup,hidden=()=>false,clock=Date.now}){
  Object.assign(this,{db,catchup,hidden,clock});
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_voice_preferences(device_id TEXT PRIMARY KEY,spoken_updates INTEGER NOT NULL DEFAULT 1);
   CREATE TABLE IF NOT EXISTS coordinator_listening(device_id TEXT PRIMARY KEY,enabled INTEGER NOT NULL,after_seq INTEGER NOT NULL,started_at INTEGER NOT NULL,last_seen INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS coordinator_spoken_offers(device_id TEXT NOT NULL,event_seq INTEGER NOT NULL,text TEXT NOT NULL,offered_at INTEGER NOT NULL,heard_at INTEGER,PRIMARY KEY(device_id,event_seq));`);
 }
 preferences(device,enabled){
  if(enabled!==undefined){if(typeof enabled!=='boolean')throw fail('Choose whether to speak work updates.');this.db.prepare('INSERT INTO coordinator_voice_preferences VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET spoken_updates=excluded.spoken_updates').run(device,enabled?1:0);}
  return {spokenUpdates:this.db.prepare('SELECT spoken_updates FROM coordinator_voice_preferences WHERE device_id=?').get(device)?.spoken_updates!==0};
 }
 max(){return Number(this.db.prepare('SELECT COALESCE(MAX(seq),0) AS n FROM session_catchup_events').get().n);}
 configure(device,enabled){
  if(typeof enabled!=='boolean')throw fail('Choose whether to listen to work updates.');enabled=enabled&&this.preferences(device).spokenUpdates;const old=this.db.prepare('SELECT * FROM coordinator_listening WHERE device_id=?').get(device),at=this.clock();
  if(enabled&&old?.enabled){this.db.prepare('UPDATE coordinator_listening SET last_seen=? WHERE device_id=?').run(at,device);return this.status(device);}
  this.db.prepare('INSERT INTO coordinator_listening VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET enabled=excluded.enabled,after_seq=excluded.after_seq,started_at=excluded.started_at,last_seen=excluded.last_seen').run(device,enabled?1:0,this.max(),at,at);return this.status(device);
 }
 status(device){const r=this.db.prepare('SELECT * FROM coordinator_listening WHERE device_id=?').get(device);return {...this.preferences(device),enabled:!!r?.enabled,startedAt:r?.started_at||null,mode:'new-material-only',modelPolling:false};}
 updates(device){
  const window=this.db.prepare('SELECT * FROM coordinator_listening WHERE device_id=?').get(device);
  const at=this.clock();if(!window?.enabled){if(window)this.db.prepare('UPDATE coordinator_listening SET last_seen=? WHERE device_id=?').run(at,device);return {enabled:false,items:[]};}
  this.db.prepare('UPDATE coordinator_listening SET last_seen=? WHERE device_id=?').run(at,device);
  const rows=this.db.prepare(`SELECT e.* FROM session_catchup_events e LEFT JOIN coordinator_spoken_offers o ON o.device_id=? AND o.event_seq=e.seq LEFT JOIN session_catchup_presented p ON p.device_id=? AND p.event_seq=e.seq LEFT JOIN session_catchup_reads r ON r.device_id=? AND r.thread_id=e.thread_id WHERE e.seq>? AND e.seq>COALESCE(r.cursor,0) AND e.resolved=0 AND p.event_seq IS NULL AND o.heard_at IS NULL AND e.kind IN ('result','answer','failure','blocked','question','approval') ORDER BY e.pending DESC,e.seq DESC LIMIT 200`).all(device,device,device,window.after_seq);
  const groups=new Set(),items=[];
  for(const row of rows){if(this.catchup.recoverableFailure?.(row.thread_id,row.turn_id)&&['failure','blocked'].includes(row.kind))continue;
   if(this.hidden(row.thread_id)||!row.pending&&row.at<Math.max(window.started_at,at-6*3600000))continue;
   const group=row.thread_id+':'+(row.pending?'request:'+row.request_id:'outcome');if(groups.has(group))continue;groups.add(group);
   const metadata=this.db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=? AND archived=0').get(row.thread_id);
   if(!metadata)continue;const thread=JSON.parse(metadata.metadata);const name=String(thread.name||'Work session').slice(0,120);
   const body=excerpt(row.text);if(!body)continue;const text=name+'. '+(row.pending?'Your input is needed. ':'')+body;
   const existing=this.db.prepare('SELECT text FROM coordinator_spoken_offers WHERE device_id=? AND event_seq=?').get(device,row.seq);
   this.db.prepare('INSERT OR IGNORE INTO coordinator_spoken_offers(device_id,event_seq,text,offered_at) VALUES(?,?,?,?)').run(device,row.seq,text,at);
   items.push({id:String(row.seq),seq:Number(row.seq),threadId:row.thread_id,turnId:row.turn_id,requestId:row.request_id,name,kind:row.kind,pending:!!row.pending,text:existing?.text||text,at:row.at});if(items.length>=6)break;
  }
  return {enabled:true,items,at,mode:'new-material-only'};
 }
 heard(device,seq){
  if(!Number.isSafeInteger(seq)||seq<1)throw fail('Invalid spoken update identity.');
  const row=this.db.prepare('SELECT o.*,e.thread_id FROM coordinator_spoken_offers o JOIN session_catchup_events e ON e.seq=o.event_seq WHERE o.device_id=? AND o.event_seq=?').get(device,seq);if(!row)throw fail('This update was not offered to this device.',404);
  this.db.prepare('UPDATE coordinator_spoken_offers SET heard_at=COALESCE(heard_at,?) WHERE device_id=? AND event_seq=?').run(this.clock(),device,seq);
  this.catchup.presented(device,[{threadId:row.thread_id,seq}]);return {ok:true,item:{threadId:row.thread_id,seq,text:row.text,heardAt:this.clock()}};
 }
 context(device){const local=this.db.prepare('SELECT e.thread_id AS threadId,e.kind,o.text,o.heard_at AS heardAt FROM coordinator_spoken_offers o JOIN session_catchup_events e ON e.seq=o.event_seq WHERE o.device_id=? AND o.heard_at IS NOT NULL ORDER BY o.heard_at DESC LIMIT 3').all(device).map(item=>({...item,environmentId:'current'}));
  const exists=this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='coordinator_remote_heard'").get();
  const remote=exists?this.db.prepare('SELECT environment_id,payload,at FROM coordinator_remote_heard WHERE device_id=? ORDER BY at DESC LIMIT 3').all(device).map(r=>({...JSON.parse(r.payload),environmentId:r.environment_id,heardAt:r.at})):[];
  return [...local,...remote].sort((a,b)=>b.heardAt-a.heardAt).slice(0,3);
 }
}
