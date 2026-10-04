// Read recent conversation text without resuming or taking ownership of a task.
export function latestMessage(thread){
  for(const turn of [...(thread.turns||[])].reverse())for(const item of [...(turn.items||[])].reverse()){
    const role=item.type==='userMessage'?'user':item.type==='agentMessage'?'assistant':null;
    if(!role)continue;
    const text=role==='assistant'?item.text:typeof item.content==='string'?item.content:(item.content||[]).map(c=>typeof c==='string'?c:c.type==='text'?c.text||'':'').join(' ');
    const preview=String(text||'').replace(/\s+/g,' ').trim().slice(0,280);
    if(preview)return {preview,previewRole:role};
  }
  return null;
}
export class ThreadPreviews {
  constructor(history,changed){this.history=history;this.changed=changed;this.cache=new Map();this.queue=new Map();this.running=new Set();}
  get(t){const old=this.cache.get(t.id);if(!old||old.updated!==t.updatedAt||t.status?.type==='active'&&Date.now()-old.at>10000){if(!this.running.has(t.id))this.queue.set(t.id,t);this.pump();}return old?.value||{preview:String(t.preview||'').replace(/\s+/g,' ').slice(0,280),previewRole:'context'};}
  pump(){while(this.running.size<3&&this.queue.size){const [id,t]=this.queue.entries().next().value;this.queue.delete(id);this.running.add(id);
    void this.history.read(t,{summary:true}).then(thread=>{const value=latestMessage(thread);this.cache.set(id,{updated:t.updatedAt,at:Date.now(),value});if(value)this.changed(id,value);}).catch(()=>{this.cache.set(id,{updated:t.updatedAt,at:Date.now(),value:this.cache.get(id)?.value});}).finally(()=>{this.running.delete(id);while(this.cache.size>300)this.cache.delete(this.cache.keys().next().value);this.pump();});
  }}
}
