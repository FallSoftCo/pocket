import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {SessionCatchup} from '../server/session-catchup.mjs';
const setup=options=>new SessionCatchup({db:new DatabaseSync(':memory:'),clock:()=>100,...options});
const item=(c,id,text,phase='final_answer',threadId='t',turnId='turn')=>c.observe({method:'item/completed',params:{threadId,turnId,item:{id,type:'agentMessage',phase,text}}});
const summary=async(c,device='a')=>(await c.snapshot(device)).sessions;
test('mid-turn read keeps useful interim and completed result unseen, even at identical timestamps',async()=>{
 const c=setup();item(c,'old','Previous answer.');c.observe({method:'turn/started',params:{threadId:'t',turn:{id:'turn'}}});
 const baseline=c.cursor('t');c.acknowledge('a','t',baseline,{source:'read'});
 item(c,'interim','The answer is Tuesday, while the deployment continues.','commentary');
 item(c,'final','Deployment is complete.');c.observe({method:'turn/completed',params:{threadId:'t',turn:{id:'turn',status:'completed',items:[{id:'final',type:'agentMessage',phase:'final_answer',text:'Deployment is complete.'}]}}});
 const sessions=await summary(c);assert.deepEqual(sessions[0].items.map(x=>x.kind),['result','answer']);assert.equal(sessions[0].working,false);assert.equal(sessions[0].items[1].text,'The answer is Tuesday, while the deployment continues.');
 assert.equal((await summary(c)).length,1);assert.equal((await summary(c,'b'))[0].items.length,3);
});
test('partial coordinator presentation acknowledges only exact supplied items, durable per-device',async()=>{
 const db=new DatabaseSync(':memory:');let c=new SessionCatchup({db});item(c,'one','First independent answer.');item(c,'two','Second independent answer.');
 const items=(await summary(c))[0].items;c.presented('a',[items[0]]);c=new SessionCatchup({db});
 assert.equal((await summary(c))[0].items.length,1);assert.equal((await summary(c,'b'))[0].items.length,2);
 assert.throws(()=>c.presented('a',[{threadId:'wrong',seq:items[1].seq}]),/belong/);
 assert.throws(()=>c.acknowledge('a','t',c.cursor('t')+1),/Invalid/);
});
test('snapshot background fetch never marks read; captured cursor cannot hide later completion',async()=>{
 const c=setup();item(c,'a','An earlier answer.');const captured=c.cursor('t');await c.snapshot('a');await c.snapshot('a');
 item(c,'b','The later final result.');c.acknowledge('a','t',captured,{source:'interaction'});
 assert.deepEqual((await summary(c))[0].items.map(x=>x.text),['The later final result.']);
});
test('failed work, pending questions, public answers are material; raw reasoning and intentions are excluded',async()=>{
 const c=setup();item(c,'noise','I’ll check the service.','commentary');c.observe({method:'item/completed',params:{threadId:'t',item:{id:'r',type:'reasoning',text:'Private thoughts'}}});c.observe({method:'item/agentMessage/delta',params:{threadId:'t',delta:'token'}});
 c.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'t',turnId:'turn',questions:[{question:'Which account should I use?'}]}});
 c.observe({method:'turn/completed',params:{threadId:'t',turn:{id:'turn',status:'failed',error:{message:'Authentication expired.'}}}});
 assert.deepEqual((await summary(c))[0].items.map(x=>x.kind),['failure','question']);
 c.resolve('q');assert.deepEqual((await summary(c))[0].items.map(x=>x.kind),['failure']);
});
test('replayed item, turn item and matching notification dedupe; revised question is a new version',async()=>{
 const c=setup();item(c,'a','A material result.');item(c,'a','A material result.');c.recordNotification({id:5,thread_id:'t',kind:'result',body:'A material result.',source_turn_id:'turn'});
 assert.equal((await summary(c))[0].items.length,1);
 c.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'t',questions:[{question:'First question?'}]}});const question=(await summary(c))[0].items[0];c.presented('a',[question]);
 c.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'t',questions:[{question:'Revised question?'}]}});
 assert.equal((await summary(c))[0].items[0].text,'Revised question?');
});
test('hidden sessions never leak; bounded retention discloses unread gaps',async()=>{
 const c=setup({hidden:id=>id==='hidden',maxPerThread:2});item(c,'h','Hidden result.','final_answer','hidden');
 for(let i=0;i<5;i++)item(c,String(i),'Result '+i);
 const s=(await summary(c))[0];assert.equal(s.items.length,2);assert.match(s.gap,/Older updates/);assert.equal(s.firstCheck,true);
 c.acknowledge('a','t',c.cursor('t'));assert.equal((await summary(c)).length,0);
});
test('presented unresolved questions stay actionable without being repeated as unseen catch-up',async()=>{
 const c=setup();c.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'t',questions:[{question:'Which region?'}]}});
 const before=await c.snapshot('a');assert.equal(before.attention[0].unseen,true);c.presented('a',before.sessions[0].items);
 const after=await c.snapshot('a');assert.equal(after.sessions.length,0);assert.equal(after.attention[0].unseen,false);assert.equal(after.attention[0].needsAttention,true);
 c.resolve('q');assert.equal((await c.snapshot('a')).attention.length,0);
});
test('notification before live completion dedupes and foreign-thread cursor is rejected',async()=>{
 const c=setup();c.recordNotification({id:1,thread_id:'t',kind:'complete',body:'The finished result.',source_turn_id:'turn'});item(c,'f','The finished result.');
 item(c,'other','Other result.','final_answer','other');item(c,'new','A new result.');
 assert.equal((await c.snapshot('a',{threadIds:['t']})).sessions[0].items.length,2);
 assert.throws(()=>c.acknowledge('a','t',c.cursor('other')),/Invalid/);
});
test('actual pending-state callback resolves obsolete questions, ordinary updates are excluded',async()=>{
 const c=setup({resolvePending:()=>false});c.recordNotification({id:1,thread_id:'t',kind:'update',body:'I am running command X.'});
 c.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'t',questions:[{question:'Already answered?'}]}});
 const snapshot=await c.snapshot('a');assert.equal(snapshot.sessions.length,0);assert.equal(snapshot.attention.length,0);
});
const history=(status,texts=[],hasEarlier=false)=>({thread:{id:'t',turns:[{id:'run',status,items:texts.map(([id,text,phase='final_answer'])=>({id,type:'agentMessage',text,phase}))}]},timeline:undefined,_pocketPage:{hasEarlier}});
test('unwatched history completion after a foreground in-progress checkpoint produces catch-up',async()=>{
 const c=setup();const baseline=history('inProgress',[['a','The allowance resets Tuesday.','commentary']]);
 const capture=c.capture('a','t',baseline);assert.equal((await c.snapshot('a')).sessions.length,0);c.acknowledgeCapture('a','t',capture.token);
 c.hydrate('a','t',history('completed',[['a','The allowance resets Tuesday.','commentary'],['b','Deployment completed successfully.']]));
 const s=(await c.snapshot('a')).sessions[0];assert.equal(s.items[0].text,'Deployment completed successfully.');assert.equal(s.workingWhenChecked,true);assert.equal(s.watchedAt,100);assert.equal(s.firstCheck,false);
});
test('background captures do not replace baseline, late/foreign/expired captures cannot promote',()=>{
 let now=100;const c=setup({clock:()=>now});const early=c.capture('a','t',history('inProgress'));now++;
 const newer=c.capture('a','t',history('completed',[['f','Already read result.']]));
 assert.equal(c.hydrate('a','t',history('completed',[['f','Already read result.']])).firstCheck,true);
 assert.throws(()=>c.acknowledgeCapture('b','t',newer.token),/belong/);c.acknowledgeCapture('a','t',newer.token);
 assert.throws(()=>c.acknowledgeCapture('a','t',early.token),/newer/);now+=600001;assert.throws(()=>c.acknowledgeCapture('a','t',newer.token),/expired/);
});
test('cross-device history imports do not mark already viewed content unseen for the other device',async()=>{
 const c=setup();c.acknowledgeCapture('a','t',c.capture('a','t',history('inProgress')).token);
 const finished=history('completed',[['f','Finished public result.']]);const bCapture=c.capture('b','t',finished);
 c.hydrate('a','t',finished);c.acknowledgeCapture('b','t',bCapture.token);
 assert.equal((await c.snapshot('a')).sessions.length,1);assert.equal((await c.snapshot('b')).sessions.length,0);
});
test('first check gives uncertain context without falsely labeling old history new; no overlap discloses gap',async()=>{
 const c=setup();const first=c.hydrate('a','t',history('completed',[['old','An old answer.']]));assert.equal(first.uncertain,true);assert.equal(first.context[0].text,'An old answer.');assert.equal((await c.snapshot('a')).sessions.length,0);
 c.acknowledgeCapture('a','t',c.capture('a','t',history('inProgress')).token);
 const later={turns:[{id:'new',status:'completed',items:[{id:'new-final',type:'agentMessage',phase:'final_answer',text:'Much later result.'}]}],_pocketPage:{hasEarlier:true}};
 assert.match(c.hydrate('a','t',later).gap,/does not overlap/);assert.match((await c.snapshot('a')).sessions[0].gap,/Older updates/);
});
test('legacy import preserves actual timestamps, exact notification read watermarks and resolved attention',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT);CREATE TABLE notification_reads(device_id TEXT,thread_id TEXT,through_id INTEGER);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);
 INSERT INTO notifications VALUES(1,'t','Old result.','complete',10,'old'),(2,'t','New result.','complete',20,'new'),(3,'t','Already resolved?','question',30,NULL);INSERT INTO notification_reads VALUES('a','t',1);INSERT INTO notification_attention VALUES(3,'q',40);`);
 const c=new SessionCatchup({db,clock:()=>1000});assert.equal(c.bootstrap().imported,3);assert.equal(c.bootstrap().imported,0);
 const a=await c.snapshot('a'),b=await c.snapshot('b');assert.deepEqual(a.sessions[0].items.map(i=>i.text),['New result.']);assert.equal(a.sessions[0].items[0].at,20);assert.equal(b.sessions[0].items.length,2);assert.equal(a.attention.length,0);
});
test('presenting the final result never resurfaces its generic completion as a repetitive catch-up',async()=>{
 const c=setup();c.observe({method:'turn/completed',params:{threadId:'t',turn:{id:'turn',status:'completed',items:[{id:'f',type:'agentMessage',phase:'final_answer',text:'The actual useful result.'}]}}});
 const snapshot=await c.snapshot('a');assert.equal(snapshot.sessions[0].items.length,1);c.presented('a',snapshot.sessions[0].items);
 assert.equal((await c.snapshot('a')).sessions.length,0);
});
test('timeline normalization excludes reasoning and plans, and older page additions are not new activity',async()=>{
 const c=setup();const view=rows=>({timeline:{rows,hasEarlier:true},thread:{turns:[]}});
 const baseline=view([{kind:'turn',turnId:'run',status:'inProgress'},{kind:'message',type:'agentMessage',itemId:'known',turnId:'run',phase:'commentary',text:'The latest known answer is Tuesday.'}]);
 c.acknowledgeCapture('a','t',c.capture('a','t',baseline).token);
 const current=view([{kind:'turn',turnId:'older',status:'completed'},{kind:'message',type:'agentMessage',itemId:'older',turnId:'older',phase:'final_answer',text:'An old expanded page result.'},{kind:'turn',turnId:'run',status:'inProgress'},{kind:'message',type:'plan',itemId:'plan',turnId:'run',text:'Plan text.'},{kind:'activity',type:'reasoning',text:'Private thought.'},...baseline.timeline.rows.slice(1),{kind:'message',type:'agentMessage',itemId:'fresh',turnId:'run',phase:'commentary',text:'A fresh intermediate public answer is now available.'}]);
 c.hydrate('a','t',current);const items=(await c.snapshot('a')).sessions[0].items;assert.deepEqual(items.map(i=>i.text),['A fresh intermediate public answer is now available.']);
});
test('notification presentations are exact and thread validated; checked-session candidates follow foreground baselines only',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);INSERT INTO notifications VALUES(1,'t','First notification result.','complete',10,'first'),(2,'t','Second notification result.','complete',20,'second');`);
 const c=new SessionCatchup({db});c.bootstrap();c.capture('a','t',history('inProgress'));assert.deepEqual(c.checkedThreadIds('a'),[]);
 c.acknowledgeCapture('a','t',c.capture('a','t',history('inProgress')).token);assert.deepEqual(c.checkedThreadIds('a'),['t']);
 assert.throws(()=>c.presentedNotification('a',1,'other'),/belong/);c.presentedNotification('a',1,'t');
 assert.deepEqual((await c.snapshot('a')).sessions[0].items.map(i=>i.text),['Second notification result.']);
});
test('played completion links exact native final result and preserves unseen intermediate answer',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);INSERT INTO notifications VALUES(1,'t','Final exact result.','complete',10,'turn');`);
 const c=new SessionCatchup({db});item(c,'interim','A useful interim answer remains available.','commentary');item(c,'final','Final exact result.');
 c.recordNotification({id:1,thread_id:'t',body:'Final exact result.',kind:'complete',source_turn_id:'turn'});
 c.presentedNotification('a',1,'t');assert.deepEqual((await c.snapshot('a')).sessions[0].items.map(i=>i.text),['A useful interim answer remains available.']);
});
test('bounded recent history can recover new useful notes without labeling pre-check notes fresh',async()=>{
 let now=100;const c=setup({clock:()=>now});c.acknowledgeCapture('a','t',c.capture('a','t',history('inProgress')).token);now=200;
 c.hydrate('a','t',{...history('inProgress'),notes:[{id:'old',turnId:'run',text:'Older preserved note.',at:10},{id:'fresh',turnId:'run',text:'Fresh useful intermediate answer.',at:150}]});
 assert.deepEqual((await c.snapshot('a')).sessions[0].items.map(i=>i.text),['Fresh useful intermediate answer.']);
});
test('actual isolated HTTP endpoints capture without reading, validate targets and acknowledge exact delivery',async t=>{
 const {mkdtempSync,writeFileSync,readFileSync,rmSync}=await import('node:fs');const {tmpdir}=await import('node:os');const {join,resolve}=await import('node:path');const {spawn}=await import('node:child_process');const {createServer}=await import('node:net');const {openStore,hash:tokenHash}=await import('../server/store.mjs');
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-catchup-http-')),fixture=join(dir,'fixture.mjs'),phase=join(dir,'phase'),log=join(dir,'calls');writeFileSync(phase,'working');
 const source=String.raw`import {createInterface} from 'node:readline';import {readFileSync,appendFileSync} from 'node:fs';const args=process.argv.slice(2),phase=args[0],log=args[1];
 const thread=id=>({id,name:id==='dns_work'?'Android network work':'Career work',cwd:'/synthetic',source:'cli',updatedAt:100,createdAt:90,status:{type:readFileSync(phase,'utf8')==='working'?'active':'idle'},turns:[{id:'run',status:readFileSync(phase,'utf8')==='working'?'inProgress':'completed',items:readFileSync(phase,'utf8')==='working'?[]:[{id:'final',type:'agentMessage',phase:'final_answer',text:'The network repair is complete.'}]}]});
 createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);if(m.id===undefined)return;appendFileSync(log,JSON.stringify({method:m.method,params:m.params})+'\n');let result={};
 if(m.method==='thread/list')result={data:[thread(m.params.cursor?'career_work':'dns_work')].map(({turns,...t})=>t),nextCursor:m.params.cursor?null:'page-two'};
 else if(m.method==='thread/read'||m.method==='thread/resume')result={thread:thread(m.params.threadId==='wrong_work'?'dns_work':m.params.threadId)};
 else if(m.method==='thread/start')result={thread:thread('voice_controller_fixture')};
 else if(m.method==='thread/turns/list'){process.stdout.write(JSON.stringify({id:m.id,error:{code:-32601,message:'thread/turns/list not supported yet'}})+'\n');return;}
 process.stdout.write(JSON.stringify({id:m.id,result})+'\n');});`;
 writeFileSync(fixture,source);const store=openStore(dir);store.db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('phone','Synthetic phone',tokenHash('fixture-token'),1);store.db.prepare('INSERT INTO notifications(thread_id,title,body,kind,created_at,source_turn_id) VALUES(?,?,?,?,?,?)').run('dns_work','Result','A previous completed result.','complete',10,'old');store.db.close();
 const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));const port=reservation.address().port;await new Promise(r=>reservation.close(r));
 const child=spawn(process.execPath,['server/index.mjs'],{cwd:resolve('.'),env:{...process.env,POCKET_DATA:dir,POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,fixture,phase,log]),PORT:String(port),POCKET_DEFAULT_CWD:'/synthetic',POCKET_VOICE_API_KEY:'',POCKET_LOCAL:'0'},detached:true,stdio:['ignore','pipe','pipe']});let output='';child.stderr.on('data',chunk=>{output+=chunk;});
 t.after(async()=>{try{process.kill(-child.pid,'SIGTERM');}catch{}await new Promise(r=>child.exitCode!==null?r():child.once('exit',r));rmSync(dir,{recursive:true,force:true});});
 await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('Fixture backend did not start: '+output)),10000);child.stdout.on('data',chunk=>{output+=chunk;if(output.includes('NextComp listening')){clearTimeout(timeout);resolve();}});child.once('exit',code=>{clearTimeout(timeout);reject(Error('Fixture exited '+code+': '+output));});});
 const request=async(path,body)=>{const r=await fetch('http://127.0.0.1:'+port+path,{headers:{Authorization:'Bearer fixture-token','Content-Type':'application/json'},...(body===undefined?{}:{method:'POST',body:JSON.stringify(body)})});return {status:r.status,data:await r.json()};};
 const first=await request('/api/threads?view=coordinator');assert.equal(first.status,200);assert.equal(first.data.nextCursor,'page-two');assert.equal(first.data.threads[0].id,'dns_work');
 const second=await request('/api/threads?view=coordinator&cursor=page-two');assert.equal(second.data.threads[0].id,'career_work');
 const listCall=readFileSync(log,'utf8').split('\n').filter(Boolean).map(line=>JSON.parse(line)).find(c=>c.method==='thread/list');assert.deepEqual(listCall.params.sourceKinds,['cli','vscode','exec','appServer','unknown']);
 const initial=await request('/api/threads/dns_work?view=timeline');assert.equal(initial.status,200);assert.ok(initial.data.catchup.token);
 const db=new DatabaseSync(join(dir,'pocket.sqlite'));t.after(()=>db.close());assert.equal(db.prepare('SELECT COUNT(*) AS n FROM session_catchup_baselines').get().n,0);
 assert.equal((await request('/api/threads/career_work/catchup/read',{token:initial.data.catchup.token})).status,400);
 const ack=await request('/api/threads/dns_work/catchup/read',{token:initial.data.catchup.token});assert.equal(ack.data.workingWhenChecked,true);
 writeFileSync(phase,'completed');const latest=await request('/api/threads/dns_work?view=timeline');assert.equal(latest.status,200);
 const observer=new SessionCatchup({db});observer.hydrate('phone','dns_work',latest.data);const unseen=await observer.snapshot('phone');assert.ok(unseen.sessions[0].items.some(i=>i.text==='The network repair is complete.'));
 assert.equal((await request('/api/voice/start',{threadId:'wrong_work'})).status,409);const focus=await request('/api/voice/start',{threadId:'dns_work'});assert.equal(focus.data.focus,'direct');assert.equal(focus.data.selected,'dns_work');const global=await request('/api/voice/start',{});assert.equal(global.data.focus,'coordinator');assert.equal(global.data.selected,null);
 assert.equal((await request('/api/notifications/1/presented',{threadId:'career_work'})).status,400);assert.equal((await request('/api/notifications/1/presented',{threadId:'dns_work'})).status,200);
 const final=(await observer.snapshot('phone')).sessions[0].items.find(i=>i.text==='The network repair is complete.');db.prepare("INSERT INTO voice_turns(device,id,hash,state,actions,created_at) VALUES(?,?,?,'completed',?,?)").run('phone','delivered_turn','fixture',JSON.stringify([{type:'catchup',items:[final]}]),100);
 assert.equal((await request('/api/voice/turns/delivered_turn/presented',{})).status,200);assert.equal((await observer.snapshot('phone')).sessions.length,0);
 assert.equal((await request('/api/voice/text',{turnId:'correct_turn',text:'Use the other work.',correctionOf:{turnId:'unknown_turn',threadId:'career_work'}})).status,404);
});
test('restart or unattached bridge state is unknown, not evidence that a pending question resolved',async()=>{
 const db=new DatabaseSync(':memory:');let c=new SessionCatchup({db});c.observe({method:'item/tool/requestUserInput',id:'unknown_pending',params:{threadId:'t',questions:[{question:'Which deployment target?'}]}});
 c=new SessionCatchup({db,resolvePending:()=>undefined});const snapshot=await c.snapshot('phone');assert.equal(snapshot.attention.length,1);assert.equal(snapshot.sessions[0].items[0].kind,'question');
});
test('short spoken summary cannot hide the unspoken notification blocker',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT,spoken_text TEXT,spoken_summary TEXT);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);INSERT INTO notifications VALUES(1,'t','Resolver fixed. Push still fails while Tailscale is connected.','complete',10,'turn','Network repair. Resolver fixed.','Resolver fixed.');`);
 const c=new SessionCatchup({db});item(c,'final','Resolver fixed. Push still fails while Tailscale is connected.');c.recordNotification(db.prepare('SELECT * FROM notifications WHERE id=1').get());
 assert.deepEqual(c.presentedNotification('a',1,'t'),{heard:true,presented:0,coverage:'summary'});
 assert.equal((await c.snapshot('a')).sessions[0].items.length,1);assert.match((await c.snapshot('a')).sessions[0].items[0].text,/Push still fails/);
});
test('stored full speech with contextual prefix acknowledges exact body while pending attention stays actionable',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT,spoken_text TEXT,spoken_summary TEXT);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);INSERT INTO notifications VALUES(1,'t','**Resolver fixed.** Push still fails.','complete',10,'turn','Network repair. Resolver fixed. Push still fails.','Resolver fixed.'),(2,'t','Which account should I use?','question',20,'turn','Account selection. Which account should I use?','Which account?');INSERT INTO notification_attention VALUES(2,'q',NULL);`);
 const c=new SessionCatchup({db});c.bootstrap();assert.equal(c.presentedNotification('a',1,'t').coverage,'full');assert.equal(c.presentedNotification('a',2,'t').coverage,'full');
 const snapshot=await c.snapshot('a');assert.equal(snapshot.sessions.length,0);assert.equal(snapshot.attention.length,1);assert.equal(snapshot.attention[0].unseen,false);assert.equal(snapshot.attention[0].needsAttention,true);
});
test('speech-cleaned code or links cannot falsely prove that all material body content was heard',async()=>{
 const db=new DatabaseSync(':memory:');db.exec(`CREATE TABLE notifications(id INTEGER PRIMARY KEY,thread_id TEXT,body TEXT,kind TEXT,created_at INTEGER,source_turn_id TEXT,spoken_text TEXT,spoken_summary TEXT);CREATE TABLE notification_attention(notification_id INTEGER,request_id TEXT,resolved_at INTEGER);`);
 const body='Resolver fixed.\n```\nPush still fails on the VPN.\n```';db.prepare('INSERT INTO notifications VALUES(?,?,?,?,?,?,?,?)').run(1,'t',body,'complete',10,'turn','Network repair. Resolver fixed.','Resolver fixed.');
 const c=new SessionCatchup({db});c.bootstrap();assert.equal(c.presentedNotification('a',1,'t').presented,0);assert.match((await c.snapshot('a')).sessions[0].items[0].text,/Push still fails/);
});
test('accepted interaction preserves unseen answers and distinguishes subsequent progress without changing checked baseline',async()=>{
 let now=100;const c=setup({clock:()=>now});c.acknowledgeCapture('a','t',c.capture('a','t',history('inProgress')).token);
 item(c,'earlier','An earlier useful answer is still unseen.','commentary');now=200;
 const interaction=c.interact('a','t');assert.equal(interaction.workingWhenInteracted,true);now=300;item(c,'later','Subsequent work has completed.');
 const s=(await c.snapshot('a')).sessions[0];assert.equal(s.lastInteractionAt,200);assert.equal(s.workingWhenInteracted,true);assert.equal(s.watchedAt,100);
 assert.deepEqual(s.items.map(i=>[i.text,i.afterLastInteraction]),[['Subsequent work has completed.',true],['An earlier useful answer is still unseen.',false]]);
 assert.equal((await c.snapshot('b')).sessions[0].lastInteractionAt,null);
});
test('automatic recovery suppresses transient failure noise but preserves useful answers and blockers',()=>{const c=setup();c.recoverableFailure=(thread,turn)=>thread==='t'&&turn==='retry';c.observe({method:'turn/completed',params:{threadId:'t',turn:{id:'retry',status:'failed',error:{message:'Selected model is at capacity'},items:[{id:'useful',type:'agentMessage',text:'The build completed; signing remains.',phase:'final'}]}}});const rows=c.db.prepare('SELECT kind,text FROM session_catchup_events').all();assert.equal(rows.length,1);assert.equal(rows[0].kind,'result');c.observe({method:'turn/completed',params:{threadId:'t',turn:{id:'actual-blocker',status:'failed',error:{message:'User action is required.'}}}});assert.equal(c.db.prepare("SELECT COUNT(*) AS n FROM session_catchup_events WHERE kind='failure'").get().n,1);});
