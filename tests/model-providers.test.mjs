import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {ModelProviders,registerModelProviderRoutes} from '../server/model-providers.mjs';
import {SessionStarts} from '../server/sessions.mjs';
import {openStore} from '../server/store.mjs';
import {HumanIngress} from '../server/human-ingress.mjs';
import {environmentRoute,scopedId} from '../server/web-environments.mjs';
import express from 'express';

function fixture(){
 const calls=[],providers=[{id:'openai',name:'OpenAI',canEdit:false,baseUrl:null,settings:{type:'openai'}}];let version='first';
 const codex={ready:false,initializeResult:{userAgent:'codex_pocket/0.171.0 (test)'},async connect(){},async call(method,params){
  calls.push({method,params});
  if(method==='modelProvider/list')return {data:providers,defaultProviderId:'openai',canChangeDefault:true};
  if(method==='config/read')return {config:{secret:'private'},layers:[{name:{type:'user'},version}]};
  if(method==='config/batchWrite'){
   assert.equal(params.expectedVersion,version);assert.equal(params.reloadUserConfig,false);version='second';
   const edit=params.edits[0],id=edit.keyPath.slice('model_providers.'.length),v=edit.value;
   providers.push({id,name:v.name,canEdit:true,baseUrl:v.base_url,settings:{type:'customGateway',auth:{mode:'externallyConfigured',envKey:null}}});return {status:'ok'};
  }
  if(method==='thread/start')return {thread:{id:'provider-thread',modelProvider:params.modelProvider}};
  throw Error('Unexpected native request '+method);
 }};
 return {codex,calls,providers,service:new ModelProviders(codex)};
}

test('paired provider setup, redacted readback and durable original creation survive a restart without changing providers',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-provider-')),f=fixture();const {db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 const ingress=new HumanIngress(db),app=express();app.use(express.json());app.use((req,_res,next)=>{req.device={id:req.get('X-Fixture-Human')?'device':'owner'};next();});app.use((req,res,next)=>ingress.middleware(req,res,next));
 const route=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);registerModelProviderRoutes(app,{codex:f.codex,route});app.use((e,_req,res,_next)=>res.status(e.status||500).json({error:e.message}));
 const server=await new Promise(r=>{const s=app.listen(0,'127.0.0.1',()=>r(s));});t.after(()=>new Promise(r=>server.close(r)));
 const url=`http://127.0.0.1:${server.address().port}/api/model-providers`;
 const body={id:'fixture',name:'Fixture provider',baseUrl:'https://provider.example/v1',apiKey:'SYNTHETIC-PRIVATE-KEY',expectedVersion:'first'};
 assert.equal((await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})).status,403);
 const save=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-Fixture-Human':'yes'},body:JSON.stringify(body)});assert.equal(save.status,200);assert(!(await save.text()).includes(body.apiKey));
 const read=await (await fetch(url)).json();assert.equal(read.providers.length,2);assert(!JSON.stringify(read).includes(body.apiKey));assert.equal(read.defaultProviderId,'openai');
 const selection=await f.service.selection({providerId:'fixture',model:'fixture-model',effort:'high'});
 let starts=new SessionStarts(db,f.codex,()=>{},ingress);const request={id:'original-request',cwd:dir,prompt:'Synthetic work',selection};const principal={kind:'paired-device',deviceId:'device'};
 starts.enqueue(request,principal);assert.equal(starts.get(request.id).state,'queued');
 assert.throws(()=>starts.enqueue({...request,selection:{providerId:'openai'}},principal),/another task or provider/);
 starts=new SessionStarts(db,f.codex,()=>{},ingress);f.codex.ready=true;await starts.flush();
 const native=f.calls.filter(c=>c.method==='thread/start');assert.equal(native.length,1);assert.equal(native[0].params.modelProvider,'fixture');assert.equal(native[0].params.model,'fixture-model');assert.equal(native[0].params.config.model_reasoning_effort,'high');
 assert.equal(starts.enqueue(request,principal).thread_id,'provider-thread');await starts.flush();assert.equal(f.calls.filter(c=>c.method==='thread/start').length,1);
 assert.equal(db.prepare('SELECT count(*) AS n FROM outgoing').get().n,1);
 assert(!JSON.stringify(db.prepare('SELECT * FROM human_admissions').all()).includes(body.apiKey));
});

test('provider configuration is version guarded, secret-safe on native rejection and never overwrites an existing provider',async()=>{
 const f=fixture(),base={id:'fixture',name:'Fixture',baseUrl:'https://fixture.example',apiKey:'SECRET',expectedVersion:'old'};
 await assert.rejects(f.service.save(base),/configuration changed/);
 assert(!f.calls.some(c=>c.method==='config/batchWrite'));
 await f.service.save({...base,expectedVersion:'first'});
 await assert.rejects(f.service.save({...base,expectedVersion:'second'}),/already exists/);
 for(const input of [{...base,id:'openai'},{...base,id:'a.b'},{...base,baseUrl:'https://name:secret@fixture.example'},{...base,baseUrl:'http://public.example'},{...base,envKey:'SECRET'}])await assert.rejects(f.service.save(input));
 const broken=fixture();broken.codex.call=async(method)=>{if(method==='config/batchWrite')throw Error('SECRET');if(method==='modelProvider/list')return {data:[],defaultProviderId:'openai'};return {layers:[{name:{type:'user'},version:'first'}]};};
 await assert.rejects(broken.service.save({...base,expectedVersion:'first'}),e=>!e.message.includes('SECRET')&&e.status===502);
});

test('older native runtimes do not pretend they can configure providers; provider model queries retain their source',async()=>{
 const f=fixture();f.codex.initializeResult.userAgent='codex_pocket/0.163.0';f.codex.call=async()=>{throw Object.assign(Error('unknown method'),{rpc:{code:-32601}});};
 assert.equal((await f.service.list()).state,'unavailable');assert.equal(await f.service.selection(),null);
 await assert.rejects(f.service.selection({providerId:'fixture'}),/Update Codex/);
 assert.deepEqual(environmentRoute('/api/environments/mac/models?threadId='+scopedId('mac','thread1')),{environmentId:'mac',path:'/api/models?threadId=thread1'});
 assert.throws(()=>environmentRoute('/api/environments/mac/models?threadId='+scopedId('other','thread1')),/different environment/);
});
