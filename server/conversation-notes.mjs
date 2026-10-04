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
 remember(thread){for(const turn of thread.turns||[])for(const item of turn.items||[])this.observe(thread.id,turn.id,item);}
 put(threadId,n){
  const text=String(n.text||'').trim().slice(0,12000),id=String(n.id||'').slice(0,512);if(!text||!id)throw Object.assign(Error('Choose a reply to keep.'),{status:400});
  const old=this.db.prepare('SELECT * FROM conversation_notes WHERE thread_id=? AND item_id=?').get(threadId,id);
  if(old?.dismissed&&!n.manual||old?.text===text&&(!n.manual||old.manual))return;
  this.db.prepare('INSERT INTO conversation_notes(thread_id,item_id,turn_id,question,text,manual,at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(thread_id,item_id) DO UPDATE SET text=excluded.text,manual=MAX(conversation_notes.manual,excluded.manual),dismissed=CASE WHEN excluded.manual=1 THEN 0 ELSE conversation_notes.dismissed END,at=excluded.at').run(threadId,id,n.turnId||null,n.question||old?.question||'',text,n.manual?1:0,this.clock());
  // Retain dismissal tombstones so revisiting history does not resurrect removed notes.
  this.db.prepare('DELETE FROM conversation_notes WHERE thread_id=? AND item_id NOT IN (SELECT item_id FROM conversation_notes WHERE thread_id=? ORDER BY manual DESC,at DESC LIMIT 100)').run(threadId,threadId);
  this.changed(threadId,this.list(threadId));
 }
 list(threadId){return this.db.prepare('SELECT item_id AS id,turn_id AS turnId,question,text,manual,at FROM conversation_notes WHERE thread_id=? AND dismissed=0 ORDER BY manual DESC,at DESC LIMIT 12').all(threadId);}
 remove(threadId,id){this.db.prepare('UPDATE conversation_notes SET dismissed=1 WHERE thread_id=? AND item_id=?').run(threadId,id);this.changed(threadId,this.list(threadId));}
}
