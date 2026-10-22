import {CoordinatorMemory} from './coordinator-memory.mjs';
import {CoordinatorSources} from './coordinator-sources.mjs';
import {CoordinatorListening} from './coordinator-listening.mjs';
export function createCoordinatorHub({db,controller,catchup,hidden,key,read,authorize,app,route}){
 const memory=new CoordinatorMemory({db,hidden,read}),listening=new CoordinatorListening({db,catchup,hidden});
 const sources=new CoordinatorSources({db,key,localApi:controller.api,authorize});
 controller.memory=memory;controller.listening=listening;controller.sources=sources;controller.discovery.memory=memory;
 const paired=req=>{if(!req.device?.id||req.device.id==='owner')throw Object.assign(Error('Use the original paired human device.'),{status:403});return req.device.id;};
 return {memory,listening,sources,mount(){
  // Mount after the existing authentication and ingress middleware.
  app.get('/api/coordinator/evidence',route(async(req,res)=>{
   const query=typeof req.query.query==='string'?req.query.query.slice(0,1000):'';
   res.json(await memory.discover({query,limit:3,hydrate:req.query.hydrate==='1'}));
  }));
  app.get('/api/coordinator/catchup',route(async(req,res)=>{
   const ids=typeof req.query.threadId==='string'?[req.query.threadId]:undefined;
   const result=await catchup.snapshot(req.device.id,{threadIds:ids,limit:6});
   for(const session of result.sessions){const saved=db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(session.threadId);session.name=saved?JSON.parse(saved.metadata).name||'Work session':'Work session';session.items=session.items.slice(0,4).map(i=>({...i,text:i.text.slice(0,1000)}));}res.json(result);
  }));
  app.post('/api/coordinator/catchup/presented',route(async(req,res)=>res.json(catchup.presented(paired(req),req.body.items))));
  app.get('/api/coordinator/receipt',route(async(req,res)=>{
   const id=req.query.id;if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{8,120}$/.test(id))return res.status(400).json({error:'Invalid original receipt identity.'});
   const principal=db.prepare("SELECT principal FROM human_admissions WHERE kind='reply' AND request_id=?").get(id);
   if(!principal||JSON.parse(principal.principal).deviceId!==paired(req))return res.status(404).json({error:'Original receipt is not available to this paired device.'});
   const row=db.prepare('SELECT id,thread_id AS threadId,state,created_at AS createdAt FROM outgoing WHERE id=?').get(id);if(!row)return res.status(404).json({error:'Original receipt is not available; absence is not proof of non-delivery.'});res.json(row);
  }));
  app.get('/api/coordinator/sources',(req,res)=>res.json({sources:sources.list(req.device.id)}));
  app.post('/api/coordinator/sources',route(async(req,res)=>res.json(await sources.register(paired(req),req.body.sources))));
  app.get('/api/coordinator/listening',route(async(req,res)=>res.json(listening.status(paired(req)))));
  app.post('/api/coordinator/voice-preferences',route(async(req,res)=>{
   const device=paired(req),result=listening.preferences(device,req.body.spokenUpdates);
   const pulse=db.prepare('SELECT last_seen FROM coordinator_listening WHERE device_id=?').get(device);if(!result.spokenUpdates||pulse&&Date.now()-pulse.last_seen<15000)listening.configure(device,result.spokenUpdates);res.json(result);
  }));
  app.post('/api/coordinator/listening',route(async(req,res)=>{
   const device=paired(req),local=listening.configure(device,req.body.enabled),remote=[];
   const roster=req.get('X-NextComp-Coordinator-Hop')==='1'?[]:sources.list(device);let next=0;
   await Promise.all(Array.from({length:Math.min(3,roster.length)},async()=>{while(next<roster.length){const source=roster[next++];try{remote.push({environmentId:source.environmentId,...await sources.call(device,source.environmentId,'/api/coordinator/listening',{enabled:local.enabled})});}catch{remote.push({environmentId:source.environmentId,available:false});}}}));
   res.json({...local,sources:remote});
  }));
  app.get('/api/coordinator/voice-updates',route(async(req,res)=>{
   const device=paired(req),local=listening.updates(device);if(!local.enabled)return res.json(local);
   const items=local.items.map(item=>({...item,environmentId:'current'})),unavailable=[];
   await Promise.all((req.get('X-NextComp-Coordinator-Hop')==='1'?[]:sources.list(device)).map(async source=>{try{const value=await sources.call(device,source.environmentId,'/api/coordinator/voice-updates');for(const item of value.items||[])items.push({...item,environmentId:source.environmentId,environmentName:source.name,text:source.name+'. '+item.text});}catch{unavailable.push(source.environmentId);}}));
   items.sort((a,b)=>Number(b.pending)-Number(a.pending)||b.at-a.at);res.json({...local,items:items.slice(0,12),unavailable});
  }));
  app.post('/api/coordinator/voice-updates/presented',route(async(req,res)=>{
   const device=paired(req),source=req.body.environmentId||'current';
   if(source==='current')return res.json(listening.heard(device,req.body.seq));
   const result=await sources.call(device,source,'/api/coordinator/voice-updates/presented',{seq:req.body.seq});
   db.exec('CREATE TABLE IF NOT EXISTS coordinator_remote_heard(device_id TEXT NOT NULL,environment_id TEXT NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,environment_id,seq))');
   // Only references originally offered through this authenticated source are
   // kept as conversational referents; remote is the heard authority.
   if(result.item)db.prepare('INSERT OR IGNORE INTO coordinator_remote_heard VALUES(?,?,?,?,?)').run(device,source,req.body.seq,JSON.stringify(result.item),Date.now());res.json(result);
  }));
  app.post('/api/voice/turns/:id/timing',route(async(req,res)=>{
   const device=paired(req);if(!['handset_first_audio','handset_response_heard'].includes(req.body.stage))return res.status(400).json({error:'Unsupported voice timing boundary.'});
   if(!controller.get(device,req.params.id))return res.status(404).json({error:'Voice turn not found.'});controller.mark(device,req.params.id,req.body.stage);res.json({ok:true});
  }));
 }};
}
