// Synthetic native immersion qualification only. No Codex, push, speech or paid provider.
import express from 'express';
import http from 'node:http';
import {WebSocketServer,WebSocket} from 'ws';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
const hash=text=>createHash('sha256').update(text).digest('hex');
export function immersionReviewPlans(port=19993,{lateRevision=0}={}){
 const examples=[
  ['unequal','Please review the latest coordinator conversation history, then inspect two new sessions.',['review the latest coordinator conversation history','rivedi la cronologia più recente del coordinatore'],['two new sessions','due nuove sessioni']],
  ['styled',`Use **the warm light**, then *review the latest coordinator conversation history*. Read [the guide](http://127.0.0.1:${port}/guide), and run \`git status\`.`,['the warm light','la luce calda'],['review the latest coordinator conversation history','rivedi la cronologia più recente del coordinatore']],
  ['controls','Working',['Working','Al lavoro']],
  ['late',`After the delayed plan arrives, review the latest coordinator conversation history. Synthetic hydration revision ${lateRevision}.`,['review the latest coordinator conversation history','rivedi la cronologia più recente del coordinatore']],
 ];
 return examples.map(([key,input,...pairs])=>{
  const original=key==='controls'?input:input+'\n\nThis follow-up paragraph stays below the changing text.';
  const spans=pairs.map(([source,target])=>({start:original.indexOf(source),end:original.indexOf(source)+source.length,source,target,unit:'phrase',note:'Synthetic authored fixture; not teacher output.',targetSegments:[]}));
  let text=original;for(const span of [...spans].reverse())text=text.slice(0,span.start)+span.target+text.slice(span.end);
  return {id:key==='controls'?'label:'+hash(original):'row:immersion-'+key,original,text,spans,version:hash(original),planVersion:'inline-replacement-v3',density:'strong',language:'it'};
 });
}
export function immersionCycleFixture({port=19993}={}){
 const app=express();app.use(express.json());const server=http.createServer(app),sockets=new WebSocketServer({noServer:true});
 const token='synthetic-immersion-polish-only',control='synthetic-immersion-control';const plans=immersionReviewPlans(port);let selected='unequal',lateHeld=true,lateRevision=0,releaseTimer=null;const requests=[];
 const thread=()=>({id:'immersion-'+selected,name:'Synthetic '+selected+' immersion',cwd:'/synthetic',status:{type:'active'},updatedAt:Date.now()/1000,preview:'Synthetic inline transition study.',watched:true});
 const availablePlans=()=>plans.filter(p=>!lateHeld||p.id!=='row:immersion-late');
 const translations=()=>({enabled:true,density:'strong',translations:availablePlans()});
 const emit=event=>{for(const s of sockets.clients)if(s.readyState===WebSocket.OPEN)s.send(JSON.stringify(event));};
 app.get('/health',(_,r)=>r.json({ok:true,synthetic:true}));
 app.use('/fixture',(q,r,next)=>q.get('x-fixture-key')===control?next():r.sendStatus(403));
 app.get('/fixture/state',(_,r)=>r.json({synthetic:true,selected,lateHeld,lateRevision,plans,requests}));
 app.post('/fixture/case',(q,r)=>{if(!['unequal','styled','late'].includes(q.body.case))return r.sendStatus(400);selected=q.body.case;emit({type:'immersion',...translations()});r.json({ok:true,thread:thread()});});
 app.post('/fixture/hydration',(q,r)=>{
  if(!['hold','release'].includes(q.body.action))return r.sendStatus(400);
  if(releaseTimer)clearTimeout(releaseTimer);releaseTimer=null;
  if(q.body.action==='hold'){
   lateHeld=true;lateRevision++;const index=plans.findIndex(p=>p.id==='row:immersion-late');plans[index]=immersionReviewPlans(port,{lateRevision}).find(p=>p.id==='row:immersion-late');
   requests.push({kind:'hydration-held',revision:lateRevision,at:Date.now()});r.json({ok:true,held:true,revision:lateRevision});return;
  }
  const delay=Number(q.body.delayMs??0);if(!Number.isInteger(delay)||delay<0||delay>60000)return r.sendStatus(400);
  const release=()=>{releaseTimer=null;lateHeld=false;requests.push({kind:'hydration-released',revision:lateRevision,at:Date.now()});emit({type:'immersion',...translations()});};
  if(delay)releaseTimer=setTimeout(release,delay);else release();r.json({ok:true,held:lateHeld,revision:lateRevision,releaseAt:Date.now()+delay});
 });
 app.get('/guide',(_,r)=>r.type('html').send('<title>Synthetic local guide</title><h1>Synthetic local guide</h1><p>No external provider.</p>'));
 app.post('/api/pair',(q,r)=>q.body.code==='SYNTHETIC-ONLY'?r.json({token,host:'Synthetic immersion review',id:'synthetic-immersion-device',local:true}):r.sendStatus(403));
 app.use('/api',(q,r,next)=>q.get('authorization')===`Bearer ${token}`?next():r.sendStatus(401));
 app.get('/api/status',(_,r)=>r.json({connected:true,host:'Synthetic immersion review',deviceId:'synthetic-immersion-device',local:true}));
 app.get('/api/threads',(_,r)=>r.json({threads:['unequal','styled','late'].map(key=>({...thread(),id:'immersion-'+key,name:'Synthetic '+key+' immersion'}))}));
 app.get('/api/threads/:id',(q,r)=>{
 const plan=plans.find(p=>p.id==='row:'+q.params.id);if(!plan)return r.sendStatus(404);
 requests.push({kind:'read',id:q.params.id,at:Date.now()});
 r.json({thread:{...thread(),id:q.params.id,turns:[]},timeline:{rows:[{id:q.params.id,turnId:'synthetic',kind:'message',phase:'final_answer',role:'assistant',text:plan.original}],hasEarlier:false},revision:1,pending:[],notes:[],notifications:[],outgoing:[],watched:true});
 });
 for(const method of ['get','post'])app[method]('/api/immersion',(_,r)=>r.json(translations()));
 app.post('/api/immersion/translate',(q,r)=>{requests.push({kind:'cached-plan-request',count:q.body.sources?.length||0});r.json({...translations(),translations:(q.body.sources||[]).flatMap(source=>availablePlans().filter(p=>p.original===source.text).map(p=>({...p,id:source.id})))});});
 app.get('/api/backends',(_,r)=>r.json({backends:[{id:'codex',configured:true,available:true},{id:'losangelex',configured:false,available:false}]}));
 for(const p of ['notifications','attention'])app.get('/api/'+p,(_,r)=>r.json({notifications:[]}));
 app.get('/api/activity',(_,r)=>r.json({items:[]}));app.get('/api/projects',(_,r)=>r.json({projects:[]}));app.get('/api/app/update',(_,r)=>r.json({available:false}));
 app.use('/api',(_,r)=>r.status(503).json({error:'Synthetic review blocks execution and speech.'}));
 server.on('upgrade',(q,s,h)=>{if(q.url!=='/events'||q.headers.authorization!==`Bearer ${token}`){s.destroy();return;}sockets.handleUpgrade(q,s,h,ws=>{ws.send(JSON.stringify({type:'status',connected:true}));ws.send(JSON.stringify({type:'immersion',...translations()}));sockets.emit('connection',ws,q);});});
 return {server,sockets,plans,close:async()=>{if(releaseTimer)clearTimeout(releaseTimer);for(const s of sockets.clients)s.terminate();await new Promise(resolve=>sockets.close(resolve));if(server.listening)await new Promise(resolve=>server.close(resolve));}};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const port=Number(process.env.IMMERSION_CYCLE_FIXTURE_PORT||19993);const fixture=immersionCycleFixture({port});fixture.server.listen(port,'127.0.0.1',()=>console.log('Synthetic immersion polish fixture on loopback:'+port));for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await fixture.close();process.exit(0);});
}
