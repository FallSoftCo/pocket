export function displayTurnError(error){
  if(!error)return undefined;
  return {message:String(error.message||'').slice(0,16000),additionalDetails:typeof error.additionalDetails==='string'?error.additionalDetails.slice(0,16000):null,codexErrorInfo:typeof error.codexErrorInfo==='string'?error.codexErrorInfo:undefined};
}
const clip=(v,n=12000)=>String(v??'').slice(-n);
const textContent=v=>typeof v==='string'?v:Array.isArray(v)?v.map(x=>typeof x==='string'?x:x.text||'').filter(Boolean).join('\n'):'';
export function itemRow(item,turnId,version=0){
  if(item._pocketRow)return {...item._pocketRow,version};
  const r={id:`${turnId}/${item.id}`,itemId:item.id,turnId,version,kind:'activity',type:item.type,status:item.status||'completed',title:'',text:'',detail:''};
  switch(item.type){
    case 'userMessage':return {...r,kind:'user',title:'You',text:textContent(item.content),clientId:item.clientId};
    case 'agentMessage':return {...r,kind:'message',title:item.phase==='final_answer'?'Codex · response':'Codex',text:item.text||'',phase:item.phase};
    case 'plan':return {...r,kind:'message',title:'Plan',text:item.text||''};
    case 'reasoning':{const summary=textContent(item.summary);return summary?{...r,title:'Thinking',text:summary}:null;}
    case 'commandExecution':return {...r,title:item.commandActions?.[0]?.type==='read'?'Read file':item.commandActions?.[0]?.type==='search'?'Search files':'Run command',text:clip(item.command,1600),detail:clip(item.aggregatedOutput),exitCode:item.exitCode,durationMs:item.durationMs,truncated:String(item.aggregatedOutput||'').length>12000};
    case 'fileChange':return {...r,title:'File changes',text:(item.changes||[]).map(c=>c.path).join('\n'),detail:clip((item.changes||[]).map(c=>`${c.path}\n${c.diff||''}`).join('\n\n'))};
    case 'mcpToolCall':return {...r,title:`${item.server} · ${item.tool}`,text:item.status==='inProgress'?'Running tool…':item.error?.message||'Tool finished',detail:clip(textContent(item.result?.content)||item.error?.message),durationMs:item.durationMs};
    case 'dynamicToolCall':return {...r,title:[item.namespace,item.tool].filter(Boolean).join(' · '),text:item.status==='inProgress'?'Running tool…':'Tool finished',detail:clip(textContent(item.contentItems)),durationMs:item.durationMs};
    case 'functionCallOutput':return {...r,title:item.name||'Tool result',detail:clip(textContent(item.output))};
    case 'webSearch':return {...r,title:'Search the web',text:item.query||item.action?.url||''};
    case 'collabAgentToolCall':return {...r,title:'Agent activity',text:item.tool||'',detail:clip(Object.values(item.agentsStates||{}).map(x=>x.message||x.status).join('\n'))};
    case 'subAgentActivity':return {...r,title:'Agent activity',text:item.agentPath||item.kind||''};
    case 'imageGeneration':return {...r,title:'Generate image',text:item.savedPath||item.failure||item.revisedPrompt||''};
    case 'imageView':return {...r,title:'View image',text:item.path||''};
    case 'contextCompaction':return {...r,title:'Context compacted',text:'Earlier context was condensed. The conversation continues here.'};
    case 'enteredReviewMode':case 'exitedReviewMode':return {...r,title:'Code review',text:item.review||''};
    case 'sleep':return {...r,title:'Waiting',text:`${Math.round((item.durationMs||0)/1000)} seconds`};
    default:return null;
  }
}
// Cache only bounded display data. Original content remains in Codex history.
export function displayItem(item,turnId){
  const original=itemRow(item,turnId);if(!original)return null;
  const row={...original};
  for(const field of ['text','detail','title'])if(typeof row[field]==='string'&&row[field].length>16000){row[field]=row[field].slice(0,16000);row.truncated=true;}
  const compact=()=>({id:item.id,type:item.type,status:item.status,phase:item.phase,text:item.type==='agentMessage'?row.text:undefined,_pocketRow:row});
  for(let shrink=0;shrink<16&&Buffer.byteLength(JSON.stringify(compact()))>64000;shrink++){row.text=row.text.slice(0,Math.floor(row.text.length/2));row.detail=row.detail.slice(0,Math.floor(row.detail.length/2));row.truncated=true;}
  return Buffer.byteLength(JSON.stringify(compact()))<=64000?compact():null;
}
export class LiveTimeline {
  constructor({maxBytes=8*1024*1024,maxThreads=32,maxItems=500,ttlMs=15*60000,clock=Date.now}={}){this.threads=new Map();this.version=0;this.bytes=0;Object.assign(this,{maxBytes,maxThreads,maxItems,ttlMs,clock});}
  clear(){this.threads.clear();this.bytes=0;}
  dropThread(id){const turns=this.threads.get(id);if(!turns)return;for(const turn of turns.values())for(const entry of turn.items.values())this.bytes-=entry.bytes;this.threads.delete(id);}
  prune(){for(const [id,turns] of this.threads)if(this.clock()-turns.touched>=this.ttlMs)this.dropThread(id);}
  removeItem(turn,id){const entry=turn.items.get(id);if(entry){this.bytes-=entry.bytes;turn.items.delete(id);}}
  store(turn,item,version){
    const compact=displayItem(item,turn.id);if(!compact)return;
    this.removeItem(turn,item.id);const bytes=Buffer.byteLength(JSON.stringify(compact));
    if(bytes>this.maxBytes)return;
    turn.items.set(item.id,{item:compact,version,bytes});this.bytes+=bytes;
    while(turn.items.size>this.maxItems)this.removeItem(turn,turn.items.keys().next().value);
    while(this.bytes>this.maxBytes||this.threads.size>this.maxThreads){
      const [id,turns]=this.threads.entries().next().value;
      if(this.threads.size>1){this.dropThread(id);continue;}
      const oldest=turns.values().next().value;this.removeItem(oldest,oldest.items.keys().next().value);
      if(!oldest.items.size&&turns.size>1)turns.delete(oldest.id);
    }
  }
  seed(thread,{throughVersion=this.version}={}){
    for(const snapshot of thread.turns||[]){
      const old=this.threads.get(thread.id)?.get(snapshot.id);
      if(old)for(const [id,entry] of old.items)if(entry.version<=throughVersion)this.removeItem(old,id);
      if(snapshot.status!=='inProgress'){if(old&&!old.items.size)this.threads.get(thread.id).delete(snapshot.id);continue;}
      const live=this.turn(thread.id,snapshot.id);live.startedAt=snapshot.startedAt;
      for(const item of snapshot.items||[])if(!live.items.has(item.id))this.store(live,item,0);
    }
  }
  turn(threadId,turnId){
    this.prune();
    let turns=this.threads.get(threadId);if(!turns)turns=new Map();
    this.threads.delete(threadId);this.threads.set(threadId,turns);turns.touched=this.clock();
    while(this.threads.size>this.maxThreads)this.dropThread(this.threads.keys().next().value);
    if(!turns.has(turnId))turns.set(turnId,{id:turnId,items:new Map(),status:'inProgress',startedAt:Date.now()/1000});
    while(turns.size>12){const old=turns.values().next().value;for(const id of old.items.keys())this.removeItem(old,id);turns.delete(old.id);}
    return turns.get(turnId);
  }
  ingest(m){
    const p=m.params||{},threadId=p.threadId,turnId=p.turnId||p.turn?.id;
    if(!threadId||!turnId)return null;
    const turn=this.turn(threadId,turnId);const version=++this.version;
    if(m.method==='turn/started'||m.method==='turn/completed'){
      Object.assign(turn,{status:p.turn.status,startedAt:p.turn.startedAt,completedAt:p.turn.completedAt,durationMs:p.turn.durationMs,error:displayTurnError(p.turn.error),version});
      for(const item of p.turn?.items||[])this.store(turn,item,version);
      return {threadId,turnId,version,turn:{id:turnId,status:turn.status,startedAt:turn.startedAt,completedAt:turn.completedAt,durationMs:turn.durationMs,error:turn.error}};
    }
    let item;
    if(m.method==='item/started'||m.method==='item/completed')item={...p.item,status:p.item.status||(m.method==='item/started'?'inProgress':'completed')};
    else if(p.itemId){
      const cached=turn.items.get(p.itemId)?.item;
      if(!cached)return {threadId,turnId,version,reload:true};
      if(m.method==='item/agentMessage/delta'||m.method==='item/plan/delta')item={...(cached||{id:p.itemId,type:m.method.includes('/plan/')?'plan':'agentMessage',status:'inProgress'}),text:(cached?.text||'')+(p.delta||'')};
      else if(m.method==='item/commandExecution/outputDelta')item={...(cached||{id:p.itemId,type:'commandExecution',status:'inProgress'}),command:cached?.command||cached?._pocketRow?.text,aggregatedOutput:clip((cached?.aggregatedOutput||cached?._pocketRow?.detail||'')+(p.delta||''))};
      else if(m.method==='item/reasoning/summaryTextDelta'){
        item={...(cached||{id:p.itemId,type:'reasoning',status:'inProgress'}),summary:[...(cached?.summary||(cached?._pocketRow?.text?[cached._pocketRow.text]:[]))]};
        item.summary[p.summaryIndex||0]=(item.summary[p.summaryIndex||0]||'')+(p.delta||'');
      }
    }
    if(!item)return null;
    delete item._pocketRow;
    // Raw reasoning and raw Responses API content never enter the phone transcript.
    if(item.type==='reasoning')delete item.content;
    this.store(turn,item,version);
    const compact=displayItem(item,turnId);const row=compact?itemRow(compact,turnId,version):null;return row?{threadId,turnId,version,row}:null;
  }
  merge(thread,{includeMissing=true,includeMissingItems=true}={}){
    this.prune();
    const result={...thread,turns:(thread.turns||[]).map(t=>({...t,items:[...(t.items||[])]}))};
    for(const [id,live] of this.threads.get(thread.id)||[]){
      let turn=result.turns.find(x=>x.id===id);
      if(!turn){if(!includeMissing)continue;turn={...live,items:[]};result.turns.push(turn);}
      if(live.version)Object.assign(turn,{status:live.status,startedAt:turn.startedAt||live.startedAt,completedAt:live.completedAt||turn.completedAt,durationMs:live.durationMs??turn.durationMs});
      for(const {item,version} of live.items.values()){
        const at=turn.items.findIndex(x=>x.id===item.id);const next={...item,_version:version};
        if(at<0){if(includeMissingItems)turn.items.push(next);}else turn.items[at]={...turn.items[at],...next,...(!next._pocketRow?{_pocketRow:undefined}:{})};
      }
    }
    return result;
  }
}
export function timelinePage(thread,pending=[],{before=null,limit=8,notifications=[]}={}){
  const turns=thread.turns||[];let end=before?turns.findIndex(t=>t.id===before):turns.length;
  if(end<0)throw Error('That history position is no longer available.');
  const start=Math.max(0,end-limit),selected=turns.slice(start,end),rows=[];
  for(const turn of selected){
    rows.push({id:`${turn.id}/header`,turnId:turn.id,kind:'turn',status:turn.status,startedAt:turn.startedAt});
    const requests=pending.filter(m=>m.params.turnId===turn.id||(!m.params.turnId&&turn.id===turns.at(-1)?.id));
    const added=new Set();
    const requestRow=m=>({id:`request/${m.id}`,turnId:turn.id,kind:'request',request:m});
    for(const item of turn.items||[]){
      const row=itemRow(item,turn.id,item._version||0);if(row)rows.push(row);
      for(const m of requests.filter(m=>m.params.itemId===item.id)){rows.push(requestRow(m));added.add(m.id);}
    }
    for(const m of requests)if(!added.has(m.id))rows.push(requestRow(m));
    for(const n of notifications.filter(n=>n.attachments?.length&&turns.findLast(t=>t.startedAt&&t.startedAt*1000<=n.created_at)?.id===turn.id).sort((a,b)=>a.created_at-b.created_at))rows.push({id:`attachment/${n.id}`,turnId:turn.id,kind:'attachments',title:n.title,attachments:n.attachments,createdAt:n.created_at});
    if(turn.status!=='inProgress'&&!turn._pocketHideEnd)rows.push({id:`${turn.id}/end`,turnId:turn.id,kind:'turnEnd',status:turn.status,durationMs:turn.durationMs,text:turn.error?.message||'',error:displayTurnError(turn.error)});
  }
  return {rows,hasEarlier:start>0,before:selected[0]?.id||null};
}
