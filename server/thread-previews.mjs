// Read recent conversation text without resuming or taking ownership of a task.
export function latestMessage(thread){
  for(const turn of [...(thread.turns||[])].reverse())for(const item of [...(turn.items||[])].reverse()){
    const role=item.type==='userMessage'?'user':item.type==='agentMessage'?'assistant':null;
    if(!role)continue;
    const text=item._pocketRow?.text??(role==='assistant'?item.text:typeof item.content==='string'?item.content:(item.content||[]).map(c=>typeof c==='string'?c:c.type==='text'?c.text||'':'').join(' '));
    const preview=String(text||'').replace(/\s+/g,' ').trim().slice(0,280);
    if(preview)return {preview,previewRole:role};
  }
  return null;
}
// Only the stock app-server's public summary is shown; never reasoning content/text.
export function thinkingSummary(item){
  const summary=item.summary??item._pocketRow?.text;
  const parts=Array.isArray(summary)?summary.map(p=>typeof p==='string'?p:p?.text||''):[typeof summary==='string'?summary:''];
  return (parts.filter(p=>p.trim()).at(-1)||'').replace(/\s+/g,' ').trim().slice(-240);
}
export function activityPreview(item){
  const running=item.status==='inProgress',failed=item.status==='failed'||item.exitCode!=null&&item.exitCode!==0;
  const compact=value=>String(value||'').replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'').replace(/\s+/g,' ').trim().slice(0,240);
  let preview,previewKind;
  switch(item.type){
    case 'commandExecution':previewKind='command';preview=`${running?'Running':failed?'Command failed':'Command finished'} · ${compact(item.command||item._pocketRow?.text)||'Command'}`;break;
    case 'fileChange':{previewKind='edit';const files=(item.changes||[]).map(c=>String(c.path||'').split('/').at(-1)).filter(Boolean);preview=`${running?'Editing':'Edited'} · ${files.length>2?files.length+' files':files.join(', ')||compact(item._pocketRow?.text)||'Files'}`;break;}
    case 'webSearch':previewKind='search';preview=`${running?'Searching':'Search'} · ${compact(item.query||item.action?.url||item._pocketRow?.text)||'Web'}`;break;
    case 'mcpToolCall':case 'dynamicToolCall':previewKind='tool';preview=`${running?'Using':failed?'Tool failed':'Tool finished'} · ${compact(item.tool||item.server||item.namespace||item._pocketRow?.title)||'Tool'}`;break;
    case 'reasoning':{const summary=thinkingSummary(item);if(!summary&&!running)return null;previewKind='thinking';preview=summary?'Thinking · '+summary:'Thinking…';break;}
    default:return null;
  }
  return {preview:preview.slice(0,280),previewRole:'activity',previewKind};
}
export function latestActivity(thread){
  for(const turn of [...(thread.turns||[])].reverse())for(const item of [...(turn.items||[])].reverse()){
    const value=latestMessage({turns:[{items:[item]}]})||activityPreview(item);if(value)return value;
  }
  return null;
}
export class ThreadPreviews {
  constructor(history,changed){this.history=history;this.changed=changed;this.cache=new Map();this.queue=new Map();this.running=new Set();this.streams=new Map();this.actions=new Map();}
  observe(event){
    const p=event.params||{},id=p.threadId||p.thread?.id;if(!id)return;
    if(event.method==='turn/completed'){for(const map of [this.streams,this.actions])for(const key of map.keys())if(key.startsWith(id+':'))map.delete(key);return;}
    let value;
    if(event.method==='item/agentMessage/delta'){
      const key=id+':'+p.itemId,previous=this.streams.get(key)||'';
      const text=(previous+String(p.delta||'')).slice(0,280);this.streams.set(key,text);
      while(this.streams.size>300)this.streams.delete(this.streams.keys().next().value);
      value={preview:text.replace(/\s+/g,' ').trim(),previewRole:'assistant'};
    }else if(event.method==='item/reasoning/summaryTextDelta'){
      const key=id+':'+p.itemId,previous=this.actions.get(key)||{id:p.itemId,type:'reasoning',status:'inProgress',summary:[]};
      const index=Number.isInteger(p.summaryIndex)&&p.summaryIndex>=0&&p.summaryIndex<100?p.summaryIndex:0;
      const summary=[...(previous.summary||[])];summary[index]=((summary[index]||'')+String(p.delta||'')).slice(-240);
      const item={...previous,summary};this.actions.set(key,item);while(this.actions.size>300)this.actions.delete(this.actions.keys().next().value);
      value=activityPreview(item);
    }else if(event.method==='item/reasoning/summaryPartAdded'){
      const key=id+':'+p.itemId,item=this.actions.get(key)||{id:p.itemId,type:'reasoning',status:'inProgress',summary:[]};
      value=activityPreview({...item,summary:[]});
    }else if(event.method==='item/commandExecution/outputDelta'){
      const key=id+':'+p.itemId,item=this.actions.get(key)||{type:'commandExecution',status:'inProgress'};
      const tail=String(p.delta||'').replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'').trim().split(/\r?\n/).filter(Boolean).at(-1);
      value=activityPreview(item);if(tail)value={...value,preview:(value.preview+' · '+tail).slice(0,280)};
    }else if(['item/started','item/completed'].includes(event.method)&&p.item){
      const item=event.method==='item/started'?{...p.item,status:'inProgress'}:p.item;
      value=latestMessage({turns:[{items:[item]}]})||activityPreview(item);
      if(event.method==='item/started'&&item.type!=='agentMessage'&&item.type!=='userMessage')this.actions.set(id+':'+item.id,item);
      while(this.actions.size>300)this.actions.delete(this.actions.keys().next().value);
      if(event.method==='item/completed')this.actions.delete(id+':'+item.id);
      if(p.item.type==='agentMessage'&&event.method==='item/started')this.streams.set(id+':'+p.item.id,p.item.text||'');
      if(event.method==='item/completed')this.streams.delete(id+':'+p.item.id);
    }
    if(!value?.preview)return;
    const old=this.cache.get(id);this.cache.set(id,{updated:old?.updated,at:Date.now(),value,liveAt:Date.now()});
    this.changed(id,{...value,activityAt:Date.now()});
  }
  get(t,{hydrate=true}={}){const old=this.cache.get(t.id);if(hydrate&&(!old||old.updated!==t.updatedAt||Date.now()-old.at>(t.status?.type==='active'||Date.now()-(t.updatedAt<1e11?t.updatedAt*1000:t.updatedAt)<15*60000?5000:60000))){if(!this.running.has(t.id))this.queue.set(t.id,t);this.pump();}return old?.value||{preview:String(t.preview||'').replace(/\s+/g,' ').slice(0,280),previewRole:'context'};}
  pump(){while(this.running.size<3&&this.queue.size){const [id,t]=this.queue.entries().next().value;this.queue.delete(id);this.running.add(id);
    const snapshot=this.cache.get(id);void (this.history.preview?this.history.preview(t):this.history.read(t,{summary:true})).then(thread=>{if(this.cache.get(id)?.liveAt!==snapshot?.liveAt||[...this.streams.keys(),...this.actions.keys()].some(key=>key.startsWith(id+':')))return;const value=latestActivity(thread);const changed=JSON.stringify(value)!==JSON.stringify(snapshot?.value);this.cache.set(id,{updated:t.updatedAt,at:Date.now(),value,liveAt:snapshot?.liveAt});if(value&&changed)this.changed(id,{...value,activityAt:Date.now()});}).catch(()=>{this.cache.set(id,{...this.cache.get(id),updated:t.updatedAt,at:Date.now()});}).finally(()=>{this.running.delete(id);while(this.cache.size>300)this.cache.delete(this.cache.keys().next().value);this.pump();});
  }}
}
