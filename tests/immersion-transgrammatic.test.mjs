import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {ImmersionWorker} from '../server/immersion-worker.mjs';
import {immersionHash,deriveImmersionText} from '../server/immersion-plan.mjs';
import {transgrammaticContract,validateTransgrammaticPlan,TRANSGRAMMATIC_VERSION} from '../server/immersion-transgrammatic.mjs';

const source='Your connection is available.';
const target='La tua connessione è disponibile.';
const segments=[['La','The'],['tua','your'],['connessione','connection'],['è','is'],['disponibile','available']].map(([target,meaning])=>({target,meaning,occurrence:0,role:'other',features:[],relations:[]}));
const plan=()=>({version:immersionHash(source),spans:[{source,occurrence:0,target,unit:'clause',note:'',targetSegments:segments}]});
test('complete Italian and Italian-order English survive worker request/cache/persistence with no extra model turn',async()=>{
 class Codex extends EventEmitter{
  calls=[];
  async connect(){}
  async call(method,args){this.calls.push({method,args});
   if(method==='account/read')return {account:{type:'chatgpt'}};
   if(method==='model/list')return {data:[]};
   if(method==='thread/start')return {thread:{id:'grammar-qa'}};
   if(method==='turn/start'){const batch=JSON.parse(args.input[0].text).sources;queueMicrotask(()=>{
    this.emit('event',{method:'item/completed',params:{threadId:'grammar-qa',item:{type:'agentMessage',text:JSON.stringify(Object.fromEntries(batch.map(row=>[row.key,{'0':{target:target.slice(0,-1),words:segments}}])))}}});
    this.emit('event',{method:'turn/completed',params:{threadId:'grammar-qa',turn:{status:'completed'}}});
   });return {turn:{id:'grammar-turn'}};}
   throw Error('Unexpected transport call '+method);
  }
 }
 const codex=new Codex(),saved=new Map(),worker=new ImmersionWorker({codex,cwd:'/owned-qa',contract:transgrammaticContract,delay:100000,load:id=>saved.get(id),save:(id,state)=>saved.set(id,state)});
 try{
  worker.setEnabled('device-a',true);worker.submit('device-a',[{id:'paragraph',text:source}]);await worker.flush();
  const result=worker.snapshot('device-a').translations[0];
  assert.equal(result.planVersion,TRANSGRAMMATIC_VERSION);assert.equal(result.text,target);
  assert.equal(result.spans[0].targetSegments.map(s=>s.meaning||s.target).join(''),'The your connection is available');
  const instructions=codex.calls.find(c=>c.method==='thread/start').args.developerInstructions;
  assert.match(instructions,/Translate ALL eligible prose/);assert.doesNotMatch(instructions,/Partial mixed-language grammar is explicitly acceptable/);
  worker.submit('device-a',[{id:'second-surface',text:source}]);await worker.flush();
  assert.equal(codex.calls.filter(c=>c.method==='turn/start').length,1);
  assert.equal(saved.get('device-a').translations.length,2);assert.equal(worker.snapshot('other-device').translations.length,0);
 }finally{worker.close();}
});
test('incomplete prose or missing English lexical meaning cannot masquerade as complete immersion',()=>{
 const row=plan();row.text=deriveImmersionText(source,row);assert.ok(validateTransgrammaticPlan(source,row));
 assert.equal(validateTransgrammaticPlan(source,{...row,spans:[{...row.spans[0],source:'connection',target:'connessione',targetSegments:[segments[2]]}],text:'Your connessione is available.'}),null);
 const missing=structuredClone(row);missing.spans[0].targetSegments[1].meaning='';
 assert.equal(validateTransgrammaticPlan(source,missing),null);
});
test('protected commands stay byte-identical while all surrounding prose is translated',()=>{
 const original='Open '+String.fromCharCode(96)+'/private/file.md'+String.fromCharCode(96)+' now.';
 const spans=[{source:'Open',target:'Apri',occurrence:0,targetSegments:[{...segments[0],target:'Apri',meaning:'Open'}]},{source:'now',target:'ora',occurrence:0,targetSegments:[{...segments[0],target:'ora',meaning:'now'}]}];
 const row={version:immersionHash(original),spans};row.text=transgrammaticContract.derive(original,row);
 assert.ok(validateTransgrammaticPlan(original,row));assert.match(row.text,/\/private\/file.md/);
 row.spans.push({source:'/private/file.md',target:'file',occurrence:0,targetSegments:[{...segments[0],target:'file',meaning:'file'}]});row.text=transgrammaticContract.derive(original,row);
 assert.equal(validateTransgrammaticPlan(original,row),null);
});

test('durable grammar jobs survive restart, back off independently, and keep their attempt budget',async()=>{
 let now=100000;const saved=new Map(),calls=[];
 const make=()=>new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000,now:()=>now,load:id=>saved.get(id),save:(id,state)=>saved.set(id,structuredClone(state))});
 let w=make();w.setEnabled('a',true);w.submit('a',[{id:'body',text:source}]);
 w.translate=async()=>{calls.push(now);return {translations:[]};};
 await w.flush();assert.equal(w.snapshot('a').jobs[0].state,'retryWaiting');
 assert.equal(saved.get('a').jobs[0].attempts,1);w.close();
 w=make();w.translate=async()=>{calls.push(now);return {translations:[]};};
 assert.equal(w.snapshot('a').jobs[0].attempts,1);await w.flush();assert.equal(calls.length,1);
 now+=30000;await w.flush();assert.equal(calls.length,2);
 // Recomposition/reopening the same source must not reset its retry budget.
 w.submit('a',[{id:'body',text:source}]);assert.equal(w.snapshot('a').jobs[0].attempts,2);
 w.submit('a',[{id:'same-content-new-surface',text:source}]);assert.equal(w.snapshot('a').jobs.find(x=>x.id==='same-content-new-surface').attempts,2);
 now+=120000;await w.flush();assert.equal(calls.length,3);assert.equal(w.snapshot('a').jobs[0].state,'unavailable');
 w.close();w=make();assert.equal(w.snapshot('a').jobs[0].state,'unavailable');
 w.submit('a',[{id:'body',text:source}]);w.translate=async()=>{throw Error('Must not retry exhausted content');};await w.flush();assert.equal(calls.length,3);
 // A different version is independently eligible, not trapped behind old work.
 w.submit('a',[{id:'body',text:source+' New.'}]);assert.equal(w.snapshot('a').jobs.find(x=>x.id==='body').attempts,0);w.setEnabled('a',false);assert.equal(saved.get('a').jobs.length,0);w.close();
});

test('restart after durable acceptance reuses grammar plans without inference',async()=>{
 const saved=new Map(),make=()=>new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000,load:id=>saved.get(id),save:(id,state)=>saved.set(id,structuredClone(state))});
 let w=make();w.setEnabled('a',true);w.submit('a',[{id:'body',text:source}]);
 w.translate=async batch=>({translations:[{...plan(),id:batch[0].id}]});await w.flush();assert.equal(saved.get('a').jobs.length,0);w.close();
 w=make();w.submit('a',[{id:'new-surface',text:source}]);w.translate=async()=>{throw Error('Cache must prevent inference');};await w.flush();
 assert.equal(w.snapshot('a').translations.find(x=>x.id==='new-surface').text,target);assert.equal(w.snapshot('a').jobs.length,0);w.close();
});

test('simultaneous surfaces share one content job and receive separate canonical surface IDs',async()=>{
 const w=new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000});
 try{w.setEnabled('a',true);w.submit('a',[{id:'chat',text:source},{id:'card',text:source}]);let inputs=0;
 w.translate=async batch=>{inputs+=batch.length;return {translations:batch.map(row=>({...plan(),id:row.id}))};};
 await w.flush();assert.equal(inputs,1);assert.deepEqual(w.snapshot('a').translations.map(x=>x.id),['chat','card']);assert.equal(w.snapshot('a').jobs.length,0);
 }finally{w.close();}
});


test('server-owned units cover Markdown without protected prose or duplicate source ambiguity',async()=>{
 const {transgrammaticUnits,prepareTransgrammaticBatch}=await import('../server/immersion-transgrammatic.mjs');
 const body='## Ready\n\nOpen '+String.fromCharCode(96)+'file.md'+String.fromCharCode(96)+' now. Read https://example.com/path and wait 20 seconds.\n\nReady again. Ready again.';
 const units=transgrammaticUnits(body);
 assert.ok(units.length>5);assert.ok(units.every(x=>!x.source.includes(String.fromCharCode(96))&&!/[#]|https:|20/.test(x.source)));
 const covered=units.map(x=>x.source).join(' ');
 for(const word of ['Ready','Open','now','Read','and','wait','seconds','again'])assert.ok(covered.includes(word));
 const prepared=prepareTransgrammaticBatch([{id:'original-id',version:immersionHash(body),text:body}]);
 assert.deepEqual(prepared.schema.properties['0'].required,units.map((_,i)=>String(i)));
 const result={'0':Object.fromEntries(units.map((u,i)=>[String(i),{target:u.source,words:[{target:u.source,occurrence:0,meaning:u.source}]}]))};
 const row=prepared.decode(result).translations[0];row.text=transgrammaticContract.derive(body,row);
 assert.equal(row.id,'original-id');assert.equal(row.text,body);assert.ok(validateTransgrammaticPlan(body,row));
});

test('visible conversation bodies outrank labels and cards',async()=>{
 const w=new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000});
 try{
  w.setEnabled('a',true);
  w.submit('a',[{id:'label:first',text:'Settings',kind:'interface label'},{id:'card:second',text:'Preview',kind:'preview'},{id:'row:old',text:'Earlier answer',kind:'message'},{id:'row:body',text:source,kind:'message'}]);
  let admitted;
  w.translate=async batch=>{admitted=batch;return {translations:[]};};await w.flush();
  assert.equal(admitted[0].text,source);
  assert.equal(w.snapshot('a').jobs.find(j=>j.id==='row:body').reason,'PLAN_NOT_RETURNED');
 }finally{w.close();}
});

test('generator revision gives rejected legacy display work one new bounded budget, not a reset on each restart',async()=>{
 const saved=new Map([['a',{enabled:true,density:'strong',translations:[],jobs:[{id:'row:old',text:source,version:immersionHash(source),planVersion:TRANSGRAMMATIC_VERSION,density:'strong',attempts:3,retryBlocked:true,expiresAt:Date.now()+60000}]}]]);
 const make=()=>new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000,load:id=>saved.get(id),save:(id,state)=>saved.set(id,structuredClone(state))});
 let w=make();assert.equal(w.snapshot('a').jobs[0].attempts,0);
 w.translate=async()=>({translations:[]});await w.flush();assert.equal(w.snapshot('a').jobs[0].attempts,1);w.close();
 w=make();assert.equal(w.snapshot('a').jobs[0].attempts,1);w.close();
});

test('phrase-owned scaffold links expansions once and rejects overlapping Italian ownership',async()=>{
 const {prepareTransgrammaticBatch}=await import('../server/immersion-transgrammatic.mjs');
 const text='I am going to the station';
 const prepared=prepareTransgrammaticBatch([{id:'phrase',version:immersionHash(text),text}]);
 const result={'0':{'0':{target:'Sto andando alla stazione',words:[
  {target:'Sto andando',occurrence:0,meaning:'Am going'},
  {target:'alla',occurrence:0,meaning:'to the'},
  {target:'stazione',occurrence:0,meaning:'station'}
 ]}}};
 const row=prepared.decode(result).translations[0];row.text=transgrammaticContract.derive(text,row);
 const validated=validateTransgrammaticPlan(text,row);assert.ok(validated);
 assert.equal(validated.spans[0].targetSegments.map(x=>x.meaning||x.target).join(''),'Am-going to-the station');
 const bad=structuredClone(row);bad.spans[0].targetSegments.push({...bad.spans[0].targetSegments[1]});
 assert.equal(validateTransgrammaticPlan(text,bad),null);
});

test('source-owned sentence punctuation is emitted once for captured teacher phrase output',async()=>{
 const {prepareTransgrammaticBatch}=await import('../server/immersion-transgrammatic.mjs');
 const text='I am taking care of the account settings.';
 const prepared=prepareTransgrammaticBatch([{id:'captured-phrase',version:immersionHash(text),text}]);
 for(const lexicalPunctuation of [false,true]){
  const result={'0':{'0':{target:'Mi sto occupando delle impostazioni dell’account.',words:[
   {target:'Mi',occurrence:0,meaning:'myself'},
   {target:'sto occupando',occurrence:0,meaning:'am taking care'},
   {target:'delle',occurrence:0,meaning:'of the'},
   {target:'impostazioni',occurrence:0,meaning:'settings'},
   {target:'dell’account'+(lexicalPunctuation?'.':''),occurrence:0,meaning:'of the account'+(lexicalPunctuation?'.':'')}
  ]}}};
  const row=prepared.decode(result).translations[0];row.text=transgrammaticContract.derive(text,row);
  const valid=validateTransgrammaticPlan(text,row);assert.ok(valid);
  assert.equal(valid.text,'Mi sto occupando delle impostazioni dell’account.');
  assert.equal(valid.spans[0].targetSegments.map(s=>s.meaning||s.target).join(''),'myself am-taking-care of-the settings of-the-account');
 }
});
