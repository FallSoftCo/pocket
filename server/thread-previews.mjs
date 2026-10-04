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
  constructor(history,changed){this.history=history;this.changed=changed;this.cache=new Map();this.queue=new Map();this.running=new Set();this.streams=new Map();}
  observe(event){
    const p=event.params||{},id=p.threadId||p.thread?.id;if(!id)return;
    let value;
    if(event.method==='item/agentMessage/delta'){
      const key=id+':'+p.itemId,previous=this.streams.get(key)||'';
      const text=(previous+String(p.delta||'')).slice(0,280);this.streams.set(key,text);
      while(this.streams.size>300)this.streams.delete(this.streams.keys().next().value);
      value={preview:text.replace(/\s+/g,' ').trim(),previewRole:'assistant'};
    }else if(['item/started','item/completed'].includes(event.method)&&p.item){
      value=latestMessage({turns:[{items:[p.item]}]});
      if(p.item.type==='agentMessage'&&event.method==='item/started')this.streams.set(id+':'+p.item.id,p.item.text||'');
      if(event.method==='item/completed')this.streams.delete(id+':'+p.item.id);
    }
    if(!value?.preview)return;
    const old=this.cache.get(id);this.cache.set(id,{updated:old?.updated,at:Date.now(),value,liveAt:Date.now()});
    this.changed(id,{...value,activityAt:Date.now()});
  }
  get(t){const old=this.cache.get(t.id);if(!old||old.updated!==t.updatedAt||t.status?.type==='active'&&Date.now()-old.at>10000){if(!this.running.has(t.id))this.queue.set(t.id,t);this.pump();}return old?.value||{preview:String(t.preview||'').replace(/\s+/g,' ').slice(0,280),previewRole:'context'};}
  pump(){while(this.running.size<3&&this.queue.size){const [id,t]=this.queue.entries().next().value;this.queue.delete(id);this.running.add(id);
    const snapshot=this.cache.get(id);void this.history.read(t,{summary:true}).then(thread=>{if(this.cache.get(id)?.liveAt!==snapshot?.liveAt||[...this.streams.keys()].some(key=>key.startsWith(id+':')))return;const value=latestMessage(thread);this.cache.set(id,{updated:t.updatedAt,at:Date.now(),value});if(value)this.changed(id,value);}).catch(()=>{this.cache.set(id,{updated:t.updatedAt,at:Date.now(),value:this.cache.get(id)?.value});}).finally(()=>{this.running.delete(id);while(this.cache.size>300)this.cache.delete(this.cache.keys().next().value);this.pump();});
  }}
}
