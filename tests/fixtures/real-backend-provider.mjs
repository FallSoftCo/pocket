// Deterministic provider boundary for journeys through the real HTTP server.
// No network clients, tools, shell execution or provider credentials.
import {createInterface} from 'node:readline';
import {readFileSync,writeFileSync,appendFileSync} from 'node:fs';
const file=process.env.NEXTCOMP_QA_PROVIDER_STATE;
if(!file||!file.startsWith('/tmp/nextcomp-real-backend-'))throw Error('Explicit disposable state required');
const read=()=>JSON.parse(readFileSync(file,'utf8'));
const output=value=>process.stdout.write(JSON.stringify(value)+'\n');
const event=(method,params)=>output({method,params});
const metadata=t=>({...t,turns:[]});
createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line);if(m.id===undefined)return;
 appendFileSync(file+'.calls.jsonl',JSON.stringify({method:m.method,params:m.params,at:Date.now()})+'\n');
 const s=read(),p=m.params||{};let result;
 try{
  const t=s.threads.find(t=>t.id===p.threadId);
  switch(m.method){
   case 'initialize':result={userAgent:'NextComp synthetic provider'};break;
   case 'thread/list':result={data:s.threads.map(metadata),nextCursor:null};break;
   case 'thread/read':case 'thread/resume':
    if(!t)throw Error('Unknown synthetic thread');
    result={thread:p.includeTurns===false||p.excludeTurns?metadata(t):t};break;
   case 'thread/turns/list':{
    if(!t)throw Error('Unknown synthetic thread');
    const turns=p.sortDirection==='asc'?[...t.turns]:[...t.turns].reverse();
    const offset=p.cursor?turns.findIndex(turn=>'at:'+turn.id===p.cursor):0,limit=p.limit||8;
    if(offset<0)throw Error('Unknown synthetic turn cursor');
    result={data:turns.slice(offset,offset+limit).map(turn=>({...turn,items:p.itemsView==='notLoaded'?[]:turn.items})),nextCursor:offset+limit<turns.length?'at:'+turns[offset+limit].id:null,backwardsCursor:turns[offset]?'at:'+turns[offset].id:null};break;
   }
   case 'thread/items/list':{
    const turn=t?.turns.find(turn=>turn.id===p.turnId);if(!turn)throw Error('Unknown synthetic turn');
    const items=p.sortDirection==='asc'?[...turn.items]:[...turn.items].reverse();
    const offset=Number(p.cursor||0),limit=p.limit||8;
    result={data:items.slice(offset,offset+limit).map(item=>({turnId:turn.id,item})),nextCursor:offset+limit<items.length?String(offset+limit):null};break;
   }
   case 'account/read':result={account:null,requiresOpenaiAuth:false};break;
   case 'account/rateLimits/read':result={rateLimits:null};break;
   case 'model/list':case 'mcpServerStatus/list':result={data:[],nextCursor:null};break;
   case 'thread/goal/get':result={goal:null};break;
   case 'turn/start':{
    if(!t)throw Error('Unknown synthetic thread');
    const id='qa-turn-'+(s.starts.length+1);
    const item={id:'qa-user-'+id,type:'userMessage',clientId:p.clientUserMessageId,content:p.input};
    const answer={id:'qa-answer-'+id,type:'agentMessage',text:'Synthetic answer to '+p.input.map(x=>x.text||'').join(''),phase:'final_answer'};
    const turn={id,status:'completed',items:[item,answer]};
    t.turns.push(turn);t.updatedAt=Date.now()/1000;s.starts.push({threadId:t.id,clientId:p.clientUserMessageId});
    writeFileSync(file,JSON.stringify(s));result={turn};
    output({id:m.id,result});
    event('turn/started',{threadId:t.id,turn:{id,status:'inProgress',items:[]}});
    event('item/started',{threadId:t.id,turnId:id,item});
    // Completion deliberately lacks clientId; backend must restore persisted identity.
    const poor={...item};delete poor.clientId;
    event('item/completed',{threadId:t.id,turnId:id,item:poor});
    event('item/completed',{threadId:t.id,turnId:id,item:answer});
    event('turn/completed',{threadId:t.id,turn});return;
   }
   default:throw Error('Unsupported synthetic RPC: '+m.method);
  }
  output({id:m.id,result});
 }catch(e){output({id:m.id,error:{code:-32601,message:e.message}});}
});
