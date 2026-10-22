import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import http from 'node:http';
import {once} from 'node:events';
import {CoordinatorMemory} from '../server/coordinator-memory.mjs';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {CoordinatorListening} from '../server/coordinator-listening.mjs';
import {CoordinatorSegments} from '../server/coordinator-segments.mjs';
import {SessionCatchup} from '../server/session-catchup.mjs';
function db(){const d=new DatabaseSync(':memory:');d.exec('CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT,archived INTEGER,seen_at INTEGER)');return d;}
function remember(d,id,name){d.prepare('INSERT OR REPLACE INTO pocket_discovered_threads VALUES(?,?,0,10)').run(id,JSON.stringify({id,name,status:{type:'idle'}}));}
function completion(threadId,id,text,phase='final_answer'){return {method:'item/completed',params:{threadId,turnId:'turn_original',item:{id,type:'agentMessage',phase,text}}};}
test('one SQLite evidence journal retrieves renamed work by public request, excludes hidden/tool data and starts no runtime from initial context',async()=>{
 const d=db();remember(d,'thread_dns','Unhelpful renamed title');remember(d,'thread_other','DNS repair');let reads=0;
 const memory=new CoordinatorMemory({db:d,hidden:id=>id==='thread_hidden',read:async()=>{reads++;throw Error('offline')}});
 memory.observe({method:'item/completed',params:{threadId:'thread_dns',turnId:'turn_original',item:{id:'request1',type:'userMessage',content:[{type:'text',text:'Repair Android resolver while preserving Tailscale'}]}}});
 memory.observe(completion('thread_hidden','hidden','Secret unseen work'));
 memory.observe({method:'item/completed',params:{threadId:'thread_dns',item:{id:'tool1',type:'commandExecution',text:'private token=not-public'}}});
 const cold=memory.initialSnapshot();assert.equal(reads,0);assert.ok(cold.catalog.length);
 const found=await memory.discover({query:'Android resolver Tailscale',limit:1,hydrate:false});assert.equal(found.sessions[0].id,'thread_dns');assert.match(found.sessions[0].messages[0].text,/preserving Tailscale/);assert.equal(reads,0);
 assert.equal(d.prepare('SELECT count(*) AS n FROM coordinator_memory').get().n,1);
});
test('new-material audio coalesces without marking fetched work heard or enrolling historical transcript backlog',()=>{
 const d=db();remember(d,'thread_alpha','Compiler');const catchup=new SessionCatchup({db:d,clock:()=>10});catchup.observe(completion('thread_alpha','old','Old result'));
 let clock=20;const audio=new CoordinatorListening({db:d,catchup,clock:()=>clock});audio.configure('phone_a',true);assert.equal(audio.updates('phone_a').items.length,0);
 clock=30;catchup.put('thread_alpha',{key:'new1',kind:'result',text:'The build passed.',at:30});catchup.put('thread_alpha',{key:'new2',kind:'result',text:'The signed update is ready.',at:31});
 const one=audio.updates('phone_a');assert.equal(one.items.length,1);assert.match(one.items[0].text,/signed update/);assert.equal(audio.updates('phone_a').items[0].seq,one.items[0].seq);
 assert.equal(d.prepare('SELECT count(*) AS n FROM session_catchup_presented').get().n,0);audio.heard('phone_a',one.items[0].seq);assert.equal(d.prepare('SELECT count(*) AS n FROM session_catchup_presented').get().n,1);
 assert.throws(()=>audio.heard('other_phone',one.items[0].seq),/not offered/);
 audio.configure('phone_a',false);assert.deepEqual(audio.updates('phone_a').items,[]);
});
test('only append-stable final sentences become speech, before item completion; commentary and private reasoning never do',()=>{
 const d=db(),segments=new CoordinatorSegments(d),active={device:'phone_a',id:'voice_original'};
 segments.event(active,{method:'item/started',params:{item:{id:'note',type:'agentMessage',phase:'commentary'}}});segments.event(active,{method:'item/agentMessage/delta',params:{itemId:'note',delta:'I might send this. '}});assert.equal(segments.list(active.device,active.id).length,0);
 segments.event(active,{method:'item/started',params:{item:{id:'final',type:'agentMessage',phase:'final_answer'}}});segments.event(active,{method:'item/agentMessage/delta',params:{itemId:'final',delta:'The compiler accepted your request. The result'}});
 assert.deepEqual(segments.list(active.device,active.id).map(s=>s.text),['The compiler accepted your request.']);
 segments.event(active,completion('thread_alpha','final','The compiler accepted your request. The result is not complete.'));
 assert.equal(segments.list(active.device,active.id).length,2);
 assert.throws(()=>segments.event(active,completion('thread_alpha','final','I changed the previous answer.')),/replaced/);
});
test('two real HTTP source authorities keep identical IDs and receipts separate; lost ACK and reopen never resend; ciphertext is device-bound',async t=>{
 let posts=0,knownReceipt=false;const server=http.createServer(async(req,res)=>{
  if(req.headers.authorization!=='Bearer original-paired-device-token')return res.writeHead(403).end('{}');
  if(req.url==='/api/status')return res.end(JSON.stringify({deviceId:'phone_a'}));
  if(req.method==='POST'){posts++;req.socket.destroy();return;}
  if(knownReceipt&&req.url.startsWith('/api/coordinator/receipt'))return res.end(JSON.stringify({id:'original_reply',state:'queued'}));
  res.end(JSON.stringify({thread:{id:'same_thread',name:'Remote source'}}));
 });server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>server.close());
 const d=db(),fetchImpl=(url,options)=>fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname,options);
 let sources=new CoordinatorSources({db:d,key:'private-installation-key',fetchImpl,localApi:async()=>({thread:{id:'same_thread',name:'Local source'}}),authorize:a=>a.voiceTurnId==='active_turn'});
 await sources.register('phone_a',[{environmentId:'mac',name:'Mac',base:'https://mac.example.ts.net',token:'original-paired-device-token'}]);
 assert.equal((await sources.call('phone_a','current','/api/threads/same_thread/native-timeline')).thread.name,'Local source');assert.equal((await sources.call('phone_a','mac','/api/threads/same_thread/native-timeline')).thread.name,'Remote source');
 const stored=d.prepare('SELECT sealed FROM coordinator_sources').get().sealed;assert.ok(!stored.includes('original-paired-device-token'));
 await assert.rejects(sources.call('another_phone','mac','/api/status'),/not delegated/);
 const actor={deviceId:'phone_a',voiceTurnId:'active_turn',voiceCallId:'original_call'},body={id:'original_reply',text:'Synthetic control; no worker exists'};
 await assert.rejects(sources.call('phone_a','mac','/api/threads/same_thread/reply',body,actor),/unconfirmed/);assert.equal(posts,1);
 sources=new CoordinatorSources({db:d,key:'private-installation-key',fetchImpl,localApi:async()=>({}),authorize:()=>true});
 await assert.rejects(sources.call('phone_a','mac','/api/threads/same_thread/reply',body,actor),/uncertain/);assert.equal(posts,1);
 knownReceipt=true;const reconciled=await sources.call('phone_a','mac','/api/threads/same_thread/reply',body,actor);assert.equal(reconciled.state,'queued');assert.equal(reconciled.notSubmittedNewCommand,true);assert.equal(posts,1);
 await assert.rejects(sources.call('phone_a','mac','/api/pairing',{},actor),/Unsupported/);
});
test('paired source authorization refusal is definitive, does not poison later human turns, and admin credentials never become a paired grant',async()=>{
 const d=db();let allowed=false,owner=false,posts=0;
 const sources=new CoordinatorSources({db:d,key:'private-installation-key',localApi:async()=>({}),authorize:()=>true,fetchImpl:async(url,options)=>{
  const path=new URL(url).pathname;
  if(path==='/api/status')return new Response(JSON.stringify({deviceId:owner?'owner':'remote_phone'}));
  if(options.method==='POST'){posts++;return new Response(JSON.stringify(allowed?{id:'new_original_reply',state:'queued'}:{error:'Original pairing is not authorized.'}),{status:allowed?202:403});}
  return new Response('{}');
 }});
 await sources.register('phone_a',[{environmentId:'remote',name:'Remote',base:'https://remote.example.ts.net',token:'original-paired-device-token'}]);
 const body={id:'original_rejected_reply',text:'Synthetic input'},actor={deviceId:'phone_a',voiceTurnId:'first_human_turn',voiceCallId:'original_call'};
 await assert.rejects(sources.call('phone_a','remote','/api/threads/same_thread/reply',body,actor),e=>e.status===403);
 await assert.rejects(sources.call('phone_a','remote','/api/threads/same_thread/reply',body,actor),e=>e.status===403);assert.equal(posts,1);
 allowed=true;const accepted=await sources.call('phone_a','remote','/api/threads/same_thread/reply',{...body,id:'new_original_reply'},{...actor,voiceTurnId:'new_human_turn'});
 assert.equal(accepted.state,'queued');assert.equal(posts,2);
 owner=true;await sources.register('phone_b',[{environmentId:'remote',name:'Remote',base:'https://remote.example.ts.net',token:'an-owner-token-is-not-human'}]);
 await assert.rejects(sources.call('phone_b','remote','/api/threads/same_thread/native-timeline'),/paired-device credential/);
 assert.equal(posts,2);
});
