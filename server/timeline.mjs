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
export class LiveTimeline {
  constructor(){this.threads=new Map();this.version=0;}
  seed(thread){for(const turn of thread.turns||[]){if(turn.status!=='inProgress')continue;const live=this.turn(thread.id,turn.id);live.startedAt=turn.startedAt;for(const item of turn.items||[])if(!live.items.has(item.id))live.items.set(item.id,{item:item.type==='reasoning'?{...item,content:undefined}:item,version:0});}}
  turn(threadId,turnId){
    if(!this.threads.has(threadId))this.threads.set(threadId,new Map());
    const turns=this.threads.get(threadId);
    if(!turns.has(turnId))turns.set(turnId,{id:turnId,items:new Map(),status:'inProgress',startedAt:Date.now()/1000});
    while(turns.size>12)turns.delete(turns.keys().next().value);
    return turns.get(turnId);
  }
  ingest(m){
    const p=m.params||{},threadId=p.threadId,turnId=p.turnId||p.turn?.id;
    if(!threadId||!turnId)return null;
    const turn=this.turn(threadId,turnId);const version=++this.version;
    if(m.method==='turn/started'||m.method==='turn/completed'){
      Object.assign(turn,{...p.turn,items:turn.items,version});
      for(const item of p.turn?.items||[])turn.items.set(item.id,{item,version});
      return {threadId,turnId,version,turn:{id:turnId,status:turn.status,startedAt:turn.startedAt,completedAt:turn.completedAt,durationMs:turn.durationMs,error:turn.error}};
    }
    let item;
    if(m.method==='item/started'||m.method==='item/completed')item={...p.item,status:p.item.status||(m.method==='item/started'?'inProgress':'completed')};
    else if(p.itemId){
      const cached=turn.items.get(p.itemId)?.item;
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
    turn.items.set(item.id,{item,version});
    const row=itemRow(item,turnId,version);return row?{threadId,turnId,version,row}:null;
  }
  merge(thread,{includeMissing=true}={}){
    const result={...thread,turns:(thread.turns||[]).map(t=>({...t,items:[...(t.items||[])]}))};
    for(const [id,live] of this.threads.get(thread.id)||[]){
      let turn=result.turns.find(x=>x.id===id);
      if(!turn){if(!includeMissing)continue;turn={...live,items:[]};result.turns.push(turn);}
      if(live.version)Object.assign(turn,{status:live.status,startedAt:turn.startedAt||live.startedAt,completedAt:live.completedAt||turn.completedAt,durationMs:live.durationMs??turn.durationMs});
      for(const {item,version} of live.items.values()){
        const at=turn.items.findIndex(x=>x.id===item.id);const next={...item,_version:version};
        if(at<0)turn.items.push(next);else turn.items[at]={...turn.items[at],...next,...(!next._pocketRow?{_pocketRow:undefined}:{})};
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
    if(turn.status!=='inProgress')rows.push({id:`${turn.id}/end`,turnId:turn.id,kind:'turnEnd',status:turn.status,durationMs:turn.durationMs,text:turn.error?.message||''});
  }
  return {rows,hasEarlier:start>0,before:selected[0]?.id||null};
}
