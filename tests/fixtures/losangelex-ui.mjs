// Synthetic team service for owned emulator review. It never invokes Codex.
import express from 'express';
import {createHash} from 'node:crypto';
const app=express();app.use(express.json());const token='synthetic-losangelex-fixture';
const tasks=[{id:'lobby',title:'Synthetic team',status:'working'}];
const events=[{id:1,kind:'message',task:'lobby',author:'maya',body:'Synthetic Maya: client work is ready for review.',visibility:'room',data:{},created_at:1},{id:2,kind:'message',task:'lobby',author:'theo',body:'Synthetic Theo: interface agreed with Maya.',visibility:'room',data:{},created_at:2}];
const receipts=new Map();const commands=[];const open=new Set();
app.get('/health',(req,res)=>res.json({synthetic:true}));
app.get('/fixture/state',(req,res)=>res.json({synthetic:true,commands,tasks,events}));
app.post('/fixture/question',(req,res)=>{const event={id:events.length+1,kind:'attention',task:'lobby',author:'maya',body:'Synthetic question: which review should we run?',visibility:'direct:maya',data:{},created_at:Date.now()};events.push(event);open.add(event.id);res.json(event);});
app.use('/hollywood/v2',(req,res,next)=>req.get('Authorization')==='Bearer '+token?next():res.status(401).json({error:'Fixture auth required'}));
app.use('/hollywood/v2',(req,res)=>{
  const path=req.path.replace(/^\/teams\/default/,'');
  const present=e=>({...e,team_id:'default',task_id:e.task,attentionState:open.has(e.id)?'open':e.kind==='attention'?'resolved':null});
  if(req.method==='GET'){
    if(path==='/teams')return res.json({teams:[{id:'default',name:'Synthetic team',coordinator:'coordinator',lobby:'lobby'}],next_cursor:null});
    if(path==='/overview')return res.json({runtimeReady:true,team:{coordinator:'Coordinate',maya:'Client',theo:'Server'},lobby:'lobby',tasks,agents:tasks.flatMap(t=>[{task:t.id,agent:'maya',status:'idle'},{task:t.id,agent:'theo',status:'working'}]),attention:[],cursor:events.length});
    if(path==='/attention')return res.json({data:events.filter(e=>open.has(e.id)).map(present),total_pending:open.size,next_cursor:null});
    if(path==='/history')return res.json({data:events.filter(e=>e.task===req.query.task&&e.visibility===(req.query.visibility||'room')).map(present),olderCursor:null,newerCursor:null});
    if(path.startsWith('/messages/')){const event=events.find(e=>e.id===Number(path.split('/')[2]));return event?res.json({event:present(event),attentionState:open.has(event.id)?'open':'resolved'}):res.status(404).json({error:'Unknown event'});}
    if(path.startsWith('/commands/'))return receipts.has(path.split('/')[2])?res.json(receipts.get(path.split('/')[2]).value):res.status(404).json({error:'Unknown command'});
  }
  if(req.method==='POST'){
    const body=req.body,digest=createHash('sha256').update(JSON.stringify(body)).digest('hex'),old=receipts.get(body.commandId);
    if(old)return old.digest===digest?res.json(old.value):res.status(409).json({error:'Command ID changed'});
    commands.push({path,body});
    if(path==='/control'){const value={confirmed:true,...body};receipts.set(body.commandId,{digest,value});return res.json(value);}
    if(path==='/tasks')tasks.push({id:body.id,title:body.title,status:'working'});
    if(path.includes('/attention/'))open.delete(Number(path.split('/')[2]));
    const event={id:events.length+1,kind:'message',task:body.task||body.id||'lobby',author:'you',body:body.body,visibility:body.visibility==='direct'?'direct:'+body.targets[0]:'room',data:{targets:body.targets||[],replyTo:body.replyTo},created_at:Date.now()};
    events.push(event);const value={...event};receipts.set(body.commandId,{digest,value});return res.status(202).json(value);
  }
  res.status(404).json({error:'Unsupported fixture operation'});
});
app.listen(Number(process.env.TEAM_FIXTURE_PORT||19992),'127.0.0.1',()=>console.log('Synthetic Losangelex fixture ready'));
