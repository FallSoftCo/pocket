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
    this.emit('event',{method:'item/completed',params:{threadId:'grammar-qa',item:{type:'agentMessage',text:JSON.stringify({translations:batch.map(row=>({...plan(),id:row.id}))})}}});
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
  assert.equal(result.spans[0].targetSegments.map(s=>s.meaning||s.target).join(''),'The your connection is available.');
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
