import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {ImmersionWorker} from '../server/immersion-worker.mjs';
import {immersionHash,deriveImmersionText} from '../server/immersion-plan.mjs';
import {transgrammaticContract,validateTransgrammaticPlan,TRANSGRAMMATIC_VERSION,grammaticalTargetSegments} from '../server/immersion-transgrammatic.mjs';

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
    this.emit('event',{method:'item/completed',params:{threadId:'grammar-qa',item:{type:'agentMessage',text:JSON.stringify(Object.fromEntries(batch.map(row=>[row.key,{'0':{target:target.slice(0,-1),words:segments.map((s,i)=>({...s,group:i<3?0:1}))}}])))}}});
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
  assert.match(instructions,/Translate ALL eligible prose/);assert.match(instructions,/small grammatical flip groups/);assert.match(instructions,/The recordings saved are ready/);assert.doesNotMatch(instructions,/containing only its complete lexical words/);assert.doesNotMatch(instructions,/Partial mixed-language grammar is explicitly acceptable/);
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
 w.translate=async batch=>{assert.equal(w.snapshot('a').jobs[0].state,'running');inputs+=batch.length;return {translations:batch.map(row=>({...plan(),id:row.id}))};};
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
 const result={'0':Object.fromEntries(units.map((u,i)=>[String(i),{target:u.source,words:[{target:u.source,occurrence:0,meaning:u.source,group:0}]}]))};
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
  {target:'Sto andando',occurrence:0,meaning:'Am going',group:0},
  {target:'alla',occurrence:0,meaning:'to the',group:1},
  {target:'stazione',occurrence:0,meaning:'station',group:1}
 ]}}};
 const missingGroup=structuredClone(result);delete missingGroup['0']['0'].words[1].group;
 assert.equal(validateTransgrammaticPlan(text,prepared.decode(missingGroup).translations[0]),null);
 const discontinuous=structuredClone(result);discontinuous['0']['0'].words[1].group=2;
 assert.equal(validateTransgrammaticPlan(text,prepared.decode(discontinuous).translations[0]),null);
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
   {target:'Mi',occurrence:0,meaning:'myself',group:0},
   {target:'sto occupando',occurrence:0,meaning:'am taking care',group:0},
   {target:'delle',occurrence:0,meaning:'of the',group:1},
   {target:'impostazioni',occurrence:0,meaning:'settings',group:1},
   {target:'dell’account'+(lexicalPunctuation?'.':''),occurrence:0,meaning:'of the account'+(lexicalPunctuation?'.':''),group:1}
  ]}}};
  const row=prepared.decode(result).translations[0];row.text=transgrammaticContract.derive(text,row);
  const valid=validateTransgrammaticPlan(text,row);assert.ok(valid);
  assert.equal(valid.text,'Mi sto occupando delle impostazioni dell’account.');
  assert.equal(valid.spans[0].targetSegments.map(s=>s.meaning||s.target).join(''),'myself am-taking-care of-the settings of-the-account');
 }
});

test('phrase plan survives contextual worker decoding without word splitting or blanket hyphenation',async()=>{
 const {prepareTransgrammaticBatch}=await import('../server/immersion-transgrammatic.mjs');
 const original='Can you open the latest message in this conversation?';
 const target='Puoi aprire l’ultimo messaggio in questa conversazione';
 const prepared=prepareTransgrammaticBatch([{id:'phrase',version:immersionHash(original),text:original}]);
 const decoded=prepared.decode({'0':{'0':{target,words:[
  {target:'Puoi',occurrence:0,meaning:'Can-you',group:0},{target:'aprire',occurrence:0,meaning:'open',group:0},
  {target:'l’ultimo',occurrence:0,meaning:'the latest',group:1},{target:'messaggio',occurrence:0,meaning:'message',group:1},
  {target:'in',occurrence:0,meaning:'in',group:2},{target:'questa',occurrence:0,meaning:'this',group:2},{target:'conversazione',occurrence:0,meaning:'conversation',group:2}
 ]}}}).translations[0];
 decoded.text=transgrammaticContract.derive(original,decoded);
 const validated=validateTransgrammaticPlan(original,decoded);assert.ok(validated);
 assert.equal(validated.planVersion,TRANSGRAMMATIC_VERSION);
 const phrases=validated.spans[0].targetSegments.filter(s=>s.meaning);
 assert.deepEqual(phrases.map(s=>s.meaning),['Can-you open','the-latest message','in this conversation']);
 assert.deepEqual(phrases.map(s=>s.target),['Puoi aprire','l’ultimo messaggio','in questa conversazione']);
 assert.match(prepared.input.instruction,/noun phrases/);
 assert.equal(validated.text,target+'?');
});

test('phrase-generator revision replaces legacy cache lazily and persists the replacement across restart',async()=>{
 const saved=new Map([['a',{enabled:true,density:'strong',translations:[{id:'body',original:source,version:immersionHash(source),text:target,planVersion:'transgrammatic-english-italian-v2',density:'strong',spans:[]}]}]]);
 const make=()=>new ImmersionWorker({codex:new EventEmitter(),cwd:'/owned-qa',contract:transgrammaticContract,delay:100000,load:id=>saved.get(id),save:(id,state)=>saved.set(id,structuredClone(state))});
 let w=make(),runs=0;
 assert.equal(w.snapshot('a').translations.length,0);
 // No automatic historical replay: only an explicit currently displayed source queues work.
 assert.equal(w.snapshot('a').jobs.length,0);
 w.submit('a',[{id:'body',text:source}]);
 w.translate=async batch=>{runs++;return {translations:[{...plan(),id:batch[0].id}]};};
 await w.flush();assert.equal(runs,1);
 assert.equal(saved.get('a').translations[0].planVersion,TRANSGRAMMATIC_VERSION);w.close();
 w=make();w.submit('a',[{id:'body',text:source}]);
 w.translate=async()=>{throw Error('Accepted phrase cache must survive reopening');};
 await w.flush();assert.equal(w.snapshot('a').translations.length,1);assert.equal(w.snapshot('a').jobs.length,0);w.close();
});

test('articles cannot be isolated from noun phrases at a flip boundary',()=>{
 const words=[{target:'Il',meaning:'The',occurrence:0,group:0},{target:'messaggio',meaning:'message',occurrence:0,group:1}];
 assert.equal(grammaticalTargetSegments('Il messaggio',words),null);
 const grouped=grammaticalTargetSegments('Il messaggio',words.map(w=>({...w,group:0})));
 assert.equal(grouped.length,1);assert.equal(grouped[0].target,'Il messaggio');assert.equal(grouped[0].meaning,'The message');
 const prep=[{target:'nella',meaning:'in-the',occurrence:0,group:0},{target:'conversazione',meaning:'conversation',occurrence:0,group:1}];
 assert.equal(grammaticalTargetSegments('nella conversazione',prep),null);
 assert.equal(grammaticalTargetSegments('nella conversazione',prep.map(w=>({...w,group:0})))[0].meaning,'in-the conversation');
});

test('overlapping English glosses are rejected without deleting Italian words or genuine repetition',async()=>{
 const {introducedEnglishRepetition}=await import('../server/immersion-quality.mjs');
 for(const [source,target,meaning]of [
  ['Your music','La tua musica','Your your music'],
  ['Another drain','Un altro drenaggio','Another another drain'],
  ['The permission has been removed','Il permesso è stato rimosso','The permission has-been been removed'],
  ['Please acknowledge the guidelines','Per favore riconosci le linee guida','Please please acknowledge the guidance guidance'],
  ['Our uploads','I nostri caricamenti','Our our uploads'],
  ['Faster tools','Strumenti più rapidi','Tools faster faster']
 ])assert.equal(introducedEnglishRepetition(source,target,meaning),true,meaning);
 for(const [source,target,meaning]of [
  ['Your music','La tua musica','The your music'],
  ['Another drain','Un altro drenaggio','An other drain'],
  ['The permission has been removed','Il permesso è stato rimosso','The permission is been removed'],
  ['I knew that that book mattered','Sapevo che quel libro importava','I-knew that that book mattered'],
  ['I had experienced enough','Avevo avuto abbastanza','I-had had enough'],
  ['Very very good','Molto molto bene','Very very good']
 ])assert.equal(introducedEnglishRepetition(source,target,meaning),false,meaning);
 const bad={version:immersionHash('Your music'),spans:[{source:'Your music',occurrence:0,target:'La tua musica',targetSegments:[{target:'La tua musica',occurrence:0,meaning:'Your your music',role:'other',features:[],relations:[]}]}]};bad.text=transgrammaticContract.derive('Your music',bad);
 assert.equal(validateTransgrammaticPlan('Your music',bad),null);
});
test('quality migration preserves valid cached teaching and existing retry budgets without inference',async()=>{
 const {validateImmersionPlan}=await import('../server/immersion-plan.mjs');
 const make=(original,target,meaning)=>{
  const raw={version:immersionHash(original),spans:[{source:original,occurrence:0,target,targetSegments:[{target,occurrence:0,meaning,role:'other',features:[],relations:[]}]}]};raw.text=transgrammaticContract.derive(original,raw);
  return {...validateImmersionPlan(original,raw,{complete:true}),id:meaning.includes('your your')?'bad':'good',density:'strong',planVersion:'transgrammatic-english-italian-v3'};
 };
 const good=make('Your connection','La tua connessione','The your connection'),bad=make('Your music','La tua musica','your your music');
 let saved={enabled:true,density:'strong',translations:[good,bad],jobs:[{id:'prior-retry',text:'Please continue.',version:immersionHash('Please continue.'),planVersion:'transgrammatic-english-italian-v3',jobVersion:'lexical-grammatical-groups-v1',density:'strong',attempts:2,nextAttemptAt:130000,expiresAt:900000}]};
 const codex=new EventEmitter();let calls=0;codex.connect=async()=>{calls++;throw Error('Cache migration must not request a model');};
 const worker=new ImmersionWorker({codex,cwd:'/owned-qa',contract:transgrammaticContract,now:()=>100000,load:()=>saved,save:(_,v)=>{saved=structuredClone(v);},delay:100000});
 try{
  const snapshot=worker.snapshot('device');assert.equal(snapshot.translations.length,1);assert.equal(snapshot.translations[0].original,good.original);assert.deepEqual(snapshot.translations[0].spans,good.spans);assert.equal(snapshot.translations[0].planVersion,TRANSGRAMMATIC_VERSION);assert.equal(snapshot.jobs[0].attempts,2);assert.equal(snapshot.jobs[0].state,'retryWaiting');assert.equal(calls,0);
  worker.submit('device',[{id:'bad',text:bad.original,kind:'message'}]);assert.equal(worker.snapshot('device').jobs.find(j=>j.id==='bad').attempts,0);assert.equal(worker.snapshot('device').jobs.find(j=>j.id==='prior-retry').attempts,2);assert.equal(calls,0);
 }finally{worker.close();}
});
test('authored control catalog has disjoint English lexical meanings',async()=>{
 const {readFileSync}=await import('node:fs');const {introducedEnglishRepetition}=await import('../server/immersion-quality.mjs');
 const rows=JSON.parse(readFileSync(new URL('../web/interface-grammar.json',import.meta.url)));
 for(const [source,parts]of Object.entries(rows))assert.equal(introducedEnglishRepetition(source,parts.map(p=>p.target).join(''),parts.map(p=>p.meaning||p.target).join('')),false,source);
});
