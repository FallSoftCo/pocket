const textOf=item=>item._pocketRow?.text??(item.type==='agentMessage'?item.text:typeof item.content==='string'?item.content:(item.content||[]).map(p=>p.type==='text'?p.text:'').join(' '));
const progress=/^(?:I(?:’|'| a)?ll\b|I will\b|Let me\b|I(?:’|')m (?:checking|looking|testing|building|implementing|investigating|working)\b)/i;
export function interimExcerpt(item){
  if(item.type!=='agentMessage'||item.phase!=='commentary')return null;
  const text=String(textOf(item)||'').trim();
  const useful=text.split(/\n\s*\n/).filter(p=>!progress.test(p.trim())).join('\n\n');
  return useful.length>=12?useful.slice(0,6000):null;
}
export class ConversationNotes {
 constructor(db,changed=()=>{},clock=Date.now){Object.assign(this,{db,changed,clock});this.questions=new Map();
  db.exec('CREATE TABLE IF NOT EXISTS conversation_notes(thread_id TEXT NOT NULL,item_id TEXT NOT NULL,turn_id TEXT,question TEXT,text TEXT NOT NULL,manual INTEGER NOT NULL DEFAULT 0,dismissed INTEGER NOT NULL DEFAULT 0,at INTEGER NOT NULL,PRIMARY KEY(thread_id,item_id))');
 }
 observe(threadId,turnId,item){
  if(!threadId||!item?.id)return;
  if(item.type==='userMessage'){
   const text=String(textOf(item)||'');const questions=text.match(/[^\n.!?]*\?/g)||[];
   this.questions.set(threadId+':'+turnId,questions.map(x=>x.trim()).filter(Boolean).join(' ').slice(0,400));
   while(this.questions.size>300)this.questions.delete(this.questions.keys().next().value);return;
  }
  const text=interimExcerpt(item);if(text)this.put(threadId,{id:item.id,turnId,text,question:this.questions.get(threadId+':'+turnId)||'',manual:false});
 }
 remember(thread){
  if(!thread?.id)return;this.db.exec('BEGIN IMMEDIATE');this.hydrating=true;this.backfillChanged=false;
  try{for(const turn of thread.turns||[])for(const item of turn.items||[])this.observe(thread.id,turn.id,item);this.db.exec('COMMIT');}
  catch(error){this.db.exec('ROLLBACK');throw error;}finally{this.hydrating=false;}
  if(this.backfillChanged)this.changed(thread.id,this.list(thread.id));
 }
 put(threadId,n){
  const text=String(n.text||'').trim().slice(0,12000),id=String(n.id||'').slice(0,512);if(!text||!id)throw Object.assign(Error('Choose a reply to keep.'),{status:400});
  const old=this.db.prepare('SELECT * FROM conversation_notes WHERE thread_id=? AND item_id=?').get(threadId,id);
  if(old?.dismissed&&!n.manual||!old?.dismissed&&old?.text===text&&(!n.manual||old.manual))return;
  this.db.prepare('INSERT INTO conversation_notes(thread_id,item_id,turn_id,question,text,manual,at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(thread_id,item_id) DO UPDATE SET text=excluded.text,manual=MAX(conversation_notes.manual,excluded.manual),dismissed=CASE WHEN excluded.manual=1 THEN 0 ELSE conversation_notes.dismissed END,at=excluded.at').run(threadId,id,n.turnId||null,n.question||old?.question||'',text,n.manual?1:0,this.clock());
  // Retain dismissal tombstones so revisiting history does not resurrect removed notes.
  this.db.prepare('DELETE FROM conversation_notes WHERE thread_id=? AND dismissed=0 AND item_id NOT IN (SELECT item_id FROM conversation_notes WHERE thread_id=? AND dismissed=0 ORDER BY manual DESC,at DESC LIMIT 100)').run(threadId,threadId);
  if(this.hydrating)this.backfillChanged=true;else this.changed(threadId,this.list(threadId));
 }
 list(threadId){return this.db.prepare('SELECT item_id AS id,turn_id AS turnId,question,text,manual,at FROM conversation_notes WHERE thread_id=? AND dismissed=0 ORDER BY manual DESC,at DESC LIMIT 12').all(threadId);}
 remove(threadId,id){
  if(typeof id!=='string'||!id.trim()||id.length>512)throw Object.assign(Error('Choose a note to remove.'),{status:400});
  // A removal can race history backfill; retain its identity even before the row arrives.
  this.db.prepare('INSERT INTO conversation_notes(thread_id,item_id,text,dismissed,at) VALUES(?,?,?,1,?) ON CONFLICT(thread_id,item_id) DO UPDATE SET dismissed=1').run(threadId,id,'',this.clock());
  const notes=this.list(threadId);this.changed(threadId,notes);return notes;
 }
}
