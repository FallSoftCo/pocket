// Standalone synthetic native-UI fixture. Never connects to Codex, Firebase or a provider.
// Run only after emulator ownership is granted: node tests/fixtures/lifecycle-ui.mjs
// Reverse the owned emulator's app endpoint to the printed loopback port separately.
import express from 'express';
import http from 'node:http';
import {WebSocketServer,WebSocket} from 'ws';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {codexConnectionError} from '../../server/connection-errors.mjs';

export function lifecycleFixture(){
  const app=express();app.use(express.json());
  const server=http.createServer(app),sockets=new WebSocketServer({noServer:true});
  const token='synthetic-lifecycle-only',control='synthetic-lifecycle-control';
  let phase='ready',partial=false,revision=1,ticks=0;
  const requests=[];
  const epoch=Math.floor(Date.now()/1000);
  let threads=Array.from({length:18},(_,i)=>({id:`lifecycle-${String(i+1).padStart(2,'0')}`,name:`Lifecycle session ${String(i+1).padStart(2,'0')}`,cwd:'/synthetic/project',preview:`Synthetic progress for session ${i+1}.`,previewRole:'assistant',previewKind:'message',status:{type:i<4?'active':'idle'},updatedAt:epoch-i*3600,watched:i%2===0,archived:false}));
  const status=()=>({connected:phase!=='draining',problem:phase==='draining'?{code:'CODEX_DRAINING',message:codexConnectionError('CODEX_DRAINING').message}:null,host:'Synthetic lifecycle review',deviceId:'synthetic-lifecycle-device',local:true});
  const emit=event=>{for(const ws of sockets.clients)if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(event));};
  app.get('/health',(_req,res)=>res.json({ok:true,synthetic:true}));
  app.use('/fixture',(req,res,next)=>req.headers['x-fixture-key']===control?next():res.sendStatus(403));
  app.get('/fixture/state',(_req,res)=>res.json({phase,partial,revision,ticks,ids:threads.map(t=>t.id),requests}));
  app.post('/fixture/action',(req,res)=>{
    const action=req.body.action;
    if(!['reset','drain','recover','reconnect','partial','complete','background','preview','discover','remove'].includes(action))return res.status(400).json({error:'Unknown synthetic action'});
    if(action==='drain'){phase='draining';emit({type:'status',...status()});}
    if(action==='recover'){phase='ready';partial=false;emit({type:'status',...status()});}
    if(action==='reconnect'){phase='ready';for(const ws of sockets.clients)ws.close(1012,'Synthetic reconnect');}
    if(action==='partial')partial=true;
    if(action==='complete')partial=false;
    if(action==='background'||action==='drain'){
      threads=threads.map((t,i)=>({...t,status:{type:action==='drain'?'idle':i%2?'active':'idle'},updatedAt:epoch+1000+i})).reverse();
      for(const t of threads)emit({type:'codex',event:{method:'thread/status/changed',params:{threadId:t.id,status:t.status}}});
    }
    if(action==='preview'){
      ticks++;threads=threads.map((t,i)=>({...t,preview:`Live synthetic update ${ticks} for ${t.name}.`,updatedAt:epoch+ticks*100+i}));
      for(const t of threads)emit({type:'sessionPreview',threadId:t.id,preview:t.preview,previewRole:'assistant',previewKind:'message',activityAt:t.updatedAt*1000});
    }
    if(action==='discover'){
      const thread={id:`lifecycle-new-${++ticks}`,name:`Lifecycle new ${ticks}`,cwd:'/synthetic/project',preview:'New synthetic work is visible.',previewRole:'assistant',previewKind:'message',status:{type:'pending'},updatedAt:epoch+10_000+ticks,watched:false,archived:false};
      threads.unshift(thread);emit({type:'sessionStarted',threadId:thread.id,thread});
    }
    if(action==='remove')threads=threads.filter(t=>t.id!==req.body.id);
    if(action==='reset'){phase='ready';partial=false;threads=threads.filter(t=>!t.id.startsWith('lifecycle-new-')).sort((a,b)=>a.id.localeCompare(b.id));emit({type:'status',...status()});}
    revision++;res.json({ok:true,action,phase,partial,revision});
  });
  app.post('/api/pair',(req,res)=>req.body.code==='LIFECYCLE'?res.json({token,id:'synthetic-lifecycle-device',host:'Synthetic lifecycle review',local:true}):res.sendStatus(401));
  app.use('/api',(req,res,next)=>req.headers.authorization===`Bearer ${token}`?next():res.sendStatus(401));
  app.get('/api/status',(_req,res)=>res.json(status()));
  app.get('/api/threads',(_req,res)=>res.json({threads:partial?threads.slice(8):threads,refreshPending:partial}));
  app.get('/api/threads/:id',(req,res)=>{
    const thread=threads.find(t=>t.id===req.params.id);if(!thread)return res.status(404).json({error:'Synthetic session not found'});
    requests.push({method:'read',id:thread.id});
    res.json({thread:{...thread,turns:[]},timeline:{rows:[{id:`${thread.id}-user`,turnId:'synthetic-turn',kind:'user',role:'user',text:'Check synthetic ongoing work.'},{id:`${thread.id}-answer`,turnId:'synthetic-turn',kind:'message',role:'assistant',text:`Ongoing synthetic work in ${thread.name} remains available.`}],hasEarlier:false},revision,notes:[],pending:[],notifications:[],outgoing:[],watched:thread.watched});
  });
  app.get('/api/activity',(_req,res)=>res.json({items:[]}));
  for(const path of ['notifications','attention'])app.get(`/api/${path}`,(_req,res)=>res.json({notifications:[]}));
  app.get('/api/projects',(_req,res)=>res.json({projects:[]}));
  app.get('/api/app/update',(_req,res)=>res.json({available:false}));
  app.post('/api/device/push',(_req,res)=>res.json({ok:true}));
  app.post('/api/threads/:id/watch',(req,res)=>{threads=threads.map(t=>t.id===req.params.id?{...t,watched:!!req.body.enabled}:t);res.json({ok:true});});
  // An accidental reply is observable but cannot submit real work or reach any provider.
  app.post('/api/threads/:id/reply',(req,res)=>{requests.push({method:'reply',id:req.params.id,requestId:req.body.id});res.status(503).json({error:phase==='draining'?codexConnectionError('CODEX_DRAINING').message:'Synthetic review blocks execution'});});
  app.use('/api',(_req,res)=>res.status(503).json({error:'Synthetic review blocks this operation'}));
  server.on('upgrade',(req,socket,head)=>{
    if(req.url!=='/events'||req.headers.authorization!==`Bearer ${token}`){socket.destroy();return;}
    sockets.handleUpgrade(req,socket,head,ws=>{ws.send(JSON.stringify({type:'status',...status()}));sockets.emit('connection',ws,req);});
  });
  return {server,sockets,token,control,close:async()=>{for(const ws of sockets.clients)ws.terminate();await new Promise(resolve=>sockets.close(resolve));if(server.listening)await new Promise(resolve=>server.close(resolve));}};
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const fixture=lifecycleFixture();const port=Number(process.env.LIFECYCLE_FIXTURE_PORT||19987);
  fixture.server.listen(port,'127.0.0.1',()=>console.log(`Synthetic lifecycle fixture listening on loopback:${fixture.server.address().port}`));
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await fixture.close();process.exit(0);});
}
