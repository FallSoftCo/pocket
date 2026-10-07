import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';
import {CoordinatorReports} from '../server/coordinator-reports.mjs';import {SessionCatchup} from '../server/session-catchup.mjs';
const start=Date.UTC(2026,9,7),minute=60000,hour=60*minute;
function setup(options={}){const db=new DatabaseSync(':memory:');db.exec("CREATE TABLE devices(id TEXT PRIMARY KEY);INSERT INTO devices VALUES('a'),('b');");let now=start;const published=[],reads=[],inventories=[];const catchup=new SessionCatchup({db,clock:()=>now});
 const make=extra=>new CoordinatorReports({db,catchup,clock:()=>now,publish:async report=>published.push(report),readThread:async id=>{reads.push(id);return {thread:{id,status:{type:'active'}}};},refreshRoster:async()=>{inventories.push(now);return {threads:[],complete:true};},...options,...extra});
 const reports=make();return {db,catchup,reports,make,published,reads,inventories,time:value=>{now=value;},advance:value=>{now+=value;},now:()=>now};}
const message=(c,threadId,id,text,phase='final_answer')=>c.observe({method:'item/completed',params:{threadId,turnId:'turn-'+threadId,item:{id,type:'agentMessage',phase,text}}});
test('report preferences default disabled and validate explicit per-device cadence',async()=>{
 const f=setup();assert.equal(f.reports.settings('a').enabled,false);assert.equal(f.reports.settings('a').intervalMinutes,30);assert.equal(f.reports.settings('a').staleAfterMinutes,120);
 await f.reports.tick();assert.equal(f.inventories.length,0);assert.equal(f.published.length,0);
 assert.throws(()=>f.reports.configure('a',{enabled:1}),/boolean/);assert.throws(()=>f.reports.configure('a',{intervalMinutes:4}),/5–1440/);assert.throws(()=>f.reports.configure('a',{staleAfterMinutes:29}),/30–10080/);assert.throws(()=>f.reports.configure('a',{device:'b'}),/Unsupported/);
 f.reports.configure('a',{enabled:true,intervalMinutes:30});assert.equal(f.reports.settings('a').nextDueAt,start);assert.equal(f.reports.settings('b').enabled,false);
});
test('reports public progress, completed results and questions without marking them read or inferring completion',async()=>{
 const f=setup();message(f.catchup,'work','interim','The allowance resets Tuesday.','commentary');message(f.catchup,'work','final','Deployment completed successfully.');
 f.catchup.observe({method:'item/tool/requestUserInput',id:'q',params:{threadId:'questions',questions:[{question:'Which account should I use?'}]}});
 f.reports.configure('a',{enabled:true});assert.equal((await f.reports.tick()).reports,1);
 const report=f.published[0];assert.match(report.response,/Deployment completed/);assert.match(report.response,/Needs your answer/);assert.match(report.response,/Tuesday/);
 assert.equal(f.db.prepare('SELECT transcript FROM voice_turns WHERE id=?').get(report.id).transcript,'');assert.equal((await f.catchup.snapshot('a')).sessions.length,2);assert.equal(f.reports.context('b').length,0);assert.equal(f.reports.context('a')[0].origin,'report');
 f.advance(30*minute);await f.reports.tick();assert.equal(f.published.length,1);assert.equal(f.inventories.length,2);message(f.catchup,'work','later','A later material answer has arrived.','commentary');f.advance(30*minute);await f.reports.tick();assert.equal(f.published.length,2);
});
test('cadence, restart and publication retry keep same report identity without duplicate voice turns',async()=>{
 let fail=true;const f=setup({publish:async()=>{if(fail)throw Error('Offline');}});message(f.catchup,'work','final','The final result.');f.reports.configure('a',{enabled:true});await f.reports.tick();
 const row=f.db.prepare('SELECT * FROM coordinator_report_runs').get();assert.equal(row.state,'prepared');assert.equal(f.db.prepare('SELECT COUNT(*) AS n FROM voice_turns').get().n,1);
 const retried=[];fail=false;const restarted=f.make({publish:async r=>retried.push(r)});await restarted.tick();assert.equal(retried[0].id,row.report_id);assert.equal(f.db.prepare('SELECT COUNT(*) AS n FROM voice_turns').get().n,1);
 await restarted.tick();assert.equal(retried.length,1);assert.equal((await f.catchup.snapshot('a')).sessions.length,1);
});
test('long excerpts offered once are not falsely acknowledged as fully presented',async()=>{
 const f=setup();message(f.catchup,'work','long','Resolver fixed. '+('Long explanation. '.repeat(70))+'Push still fails on the VPN.');f.reports.configure('a',{enabled:true});await f.reports.tick();
 const report=f.published[0];assert.match(report.response,/Open the session/);assert.equal(report.actions.filter(a=>a.type==='catchup').length,0);assert.equal((await f.catchup.snapshot('a')).sessions[0].items.length,1);
 f.advance(30*minute);await f.reports.tick();assert.equal(f.published.length,1);
});
test('known untouched live CLI and managed child work gets bounded read-only status checks',async()=>{
 const f=setup({refreshRoster:async()=>({complete:true,threads:[{id:'cli',name:'CLI work',source:'cli',updatedAt:(start-3*hour)/1000,status:{type:'active'}},{id:'child',name:'Assigned child',source:'subAgent',parentThreadId:'parent',updatedAt:(start-3*hour)/1000,status:{type:'active'}}]})});
 f.reports.configure('a',{enabled:true});await f.reports.tick();assert.deepEqual(f.reads.sort(),['child','cli']);assert.match(f.published[0].response,/Latest checked state: Working/);assert.match(f.published[0].response,/Quiet alone does not establish a failure/);assert.equal(f.published[0].actions.every(a=>a.type==='route'&&a.operation==='read'&&a.manualAskUpdate),true);
 f.advance(30*minute);await f.reports.tick();assert.equal(f.reads.length,2);assert.equal(f.published.length,1);
});
test('fresh roster is shared across configured devices; checks limited to three and cached per-device results reused',async()=>{
 const threads=Array.from({length:6},(_,i)=>({id:'t'+i,name:'Task '+i,updatedAt:(start-3*hour)/1000,status:{type:'active'}}));let inventoryReads=0;
 const f=setup({refreshRoster:async()=>{inventoryReads++;return {threads,complete:true};}});f.reports.configure('a',{enabled:true});f.reports.configure('b',{enabled:true});
 assert.equal((await f.reports.tick()).statusChecks,3);assert.equal(f.reads.length,3);assert.equal(inventoryReads,1);assert.equal(f.published.length,2);assert.equal(f.reports.context('a')[0].sources.length,3);assert.equal(f.reports.context('b')[0].sources.length,3);
});
test('inventory truncation and failures expose incomplete scope instead of claiming all work',async()=>{
 const f=setup({refreshRoster:async()=>({complete:false,truncated:true,nextCursor:'remaining-page',reason:'More sessions remain outside this bounded inventory.',threads:[]})});message(f.catchup,'work','first','An observed result.');f.reports.configure('a',{enabled:true});await f.reports.tick();
 assert.match(f.published[0].response,/Coverage is partial/);assert.equal(f.reports.settings('a').inventory.nextCursor,'remaining-page');assert.equal(f.reports.context('a')[0].scope.complete,false);
 f.advance(30*minute);message(f.catchup,'work','later','A later result.');const failed=f.make({refreshRoster:async()=>{throw Error('Offline');}});await failed.tick();assert.equal(failed.settings('a').inventory.unverified,true);assert.match(f.published[1].response,/could not be verified/);
});
test('unknown status and interrupted collecting restart remain explicitly unverified without mutations',async()=>{
 const f=setup({readThread:async()=>{throw Error('Transport unavailable.');}});f.catchup.interact('a','assigned');f.advance(3*hour);f.reports.configure('a',{enabled:true});await f.reports.tick();assert.match(f.published[0].response,/could not be verified/);assert.equal(f.published[0].actions[0].status,'unverified');
 const g=setup();g.catchup.interact('a','assigned');g.advance(3*hour);g.reports.configure('a',{enabled:true});g.db.prepare("INSERT INTO coordinator_report_runs VALUES(?,?,?,'collecting',NULL,?)").run('a','recovered','report-recovered',g.now());g.db.prepare("INSERT INTO coordinator_report_checks(thread_id,checked_at,status,detail) VALUES(?,?,'checking','')").run('assigned',g.now());await g.reports.tick();assert.match(g.published[0].response,/interrupted/);assert.equal(g.reads.length,0);
});
test('overlap guard prevents concurrent scheduling and reporting context expires after one day',async()=>{
 let release;const wait=new Promise(r=>{release=r;});const f=setup({refreshRoster:async()=>{await wait;return {complete:true,threads:[]};}});message(f.catchup,'work','final','Result.');f.reports.configure('a',{enabled:true});const running=f.reports.tick();assert.equal((await f.reports.tick()).busy,true);release();await running;assert.equal(f.published.length,1);f.advance(25*hour);assert.equal(f.reports.context('a').length,0);
});
test('revoked devices cannot keep scheduling stale checks or publish prepared report retries',async()=>{
 const f=setup({publish:async()=>{throw Error('Offline');}});message(f.catchup,'work','result','A pending publication result.');f.reports.configure('a',{enabled:true});await f.reports.tick();assert.equal(f.db.prepare('SELECT state FROM coordinator_report_runs').get().state,'prepared');
 f.db.prepare('DELETE FROM devices WHERE id=?').run('a');let sent=0;const restarted=f.make({publish:async()=>{sent++;}});f.advance(3*hour);await restarted.tick();assert.equal(sent,0);assert.equal(restarted.settings('a').enabled,false);assert.equal(f.db.prepare('SELECT state FROM coordinator_report_runs').get().state,'cancelled');assert.throws(()=>restarted.configure('a',{enabled:true}),/Pair/);
});
test('revocation while a status read is in flight prevents persistence and publication',async()=>{
 let release;const wait=new Promise(r=>{release=r;});const f=setup({readThread:async id=>{await wait;return {thread:{id,status:{type:'active'}}};}});f.catchup.interact('a','assigned');f.advance(3*hour);f.reports.configure('a',{enabled:true});const tick=f.reports.tick();await new Promise(r=>setImmediate(r));f.db.prepare('DELETE FROM devices WHERE id=?').run('a');release();await tick;assert.equal(f.published.length,0);assert.equal(f.db.prepare('SELECT COUNT(*) AS n FROM voice_turns').get().n,0);
});
test('actual isolated scheduler stages reports and targeted HTTP transport enforces device ownership',async t=>{
 const {mkdtempSync,writeFileSync,rmSync}=await import('node:fs');const {tmpdir}=await import('node:os');const {join,resolve}=await import('node:path');const {spawn}=await import('node:child_process');const {createServer}=await import('node:net');const {openStore,hash:tokenHash}=await import('../server/store.mjs');const {WebSocket}=await import('ws');
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-report-http-')),fixture=join(dir,'fixture.mjs'),accelerator=join(dir,'scheduler-clock.mjs');
 writeFileSync(fixture,`import{createInterface}from'node:readline';createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);if(m.id===undefined)return;const result=m.method==='thread/list'?{data:[],nextCursor:null}:{};process.stdout.write(JSON.stringify({id:m.id,result})+'\\n');});`);
 // Only the owned test process accelerates the minute dispatcher; cadence timestamps stay real.
 writeFileSync(accelerator,"const original=globalThis.setInterval;globalThis.setInterval=(fn,ms,...args)=>original(fn,ms===60000&&String(fn).includes('coordinatorReports.tick')?20:ms,...args);\n");
 const store=openStore(dir);store.db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('phone_a','Synthetic A',tokenHash('token-a'),1);store.db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('phone_b','Synthetic B',tokenHash('token-b'),1);const admin=store.secrets.adminToken;
 const catchup=new SessionCatchup({db:store.db});message(catchup,'work','final','A real observed result for this fixture.');store.db.close();
 const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));const port=reservation.address().port;await new Promise(r=>reservation.close(r));
 const child=spawn(process.execPath,['--import',accelerator,'server/index.mjs'],{cwd:resolve('.'),env:{...process.env,POCKET_DATA:dir,POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,fixture]),PORT:String(port),POCKET_LOCAL:'0',POCKET_VOICE_API_KEY:''},detached:true,stdio:['ignore','pipe','pipe']});let output='';child.stderr.on('data',chunk=>{output+=chunk;});
 t.after(async()=>{try{process.kill(-child.pid,'SIGTERM');}catch{}await new Promise(r=>child.exitCode!==null?r():child.once('exit',r));rmSync(dir,{recursive:true,force:true});});
 await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('Fixture did not start: '+output)),10000);child.stdout.on('data',chunk=>{output+=chunk;if(output.includes('NextComp listening')){clearTimeout(timeout);resolve();}});child.once('exit',code=>{clearTimeout(timeout);reject(Error('Fixture exited '+code+': '+output));});});
 const request=async(path,body,token='token-a')=>{const r=await fetch('http://127.0.0.1:'+port+path,{headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body===undefined?{}:{method:'POST',body:JSON.stringify(body)})});return {status:r.status,data:await r.json()};};
 const connect=token=>new Promise((resolve,reject)=>{const socket=new WebSocket('ws://127.0.0.1:'+port+'/events',{headers:{Authorization:'Bearer '+token}});socket.messages=[];socket.on('message',raw=>socket.messages.push(JSON.parse(String(raw))));socket.once('open',()=>resolve(socket));socket.once('error',reject);});
 const a=await connect('token-a'),b=await connect('token-b');t.after(()=>{a.terminate();b.terminate();});
 assert.equal((await request('/api/coordinator/reporting')).data.enabled,false);assert.equal((await request('/api/coordinator/reporting/phone_b',{enabled:true})).status,403);
 assert.equal((await request('/api/coordinator/reporting/unknown',{enabled:true},admin)).status,403);
 assert.equal((await request('/api/coordinator/reporting/phone_a',{enabled:true,intervalMinutes:30,staleAfterMinutes:120},admin)).status,200);
 const deadline=Date.now()+5000;while(!a.messages.some(m=>m.type==='coordinatorReport')&&Date.now()<deadline)await new Promise(r=>setTimeout(r,20));
 const envelope=a.messages.find(m=>m.type==='coordinatorReport');assert.ok(envelope,'actual dispatcher did not publish a report');assert.equal(b.messages.some(m=>m.type==='coordinatorReport'),false);assert.equal(a.messages.some(m=>m.type==='notification'&&m.notification?.kind==='coordinator_report'),false);
 assert.equal((await request('/api/voice/history')).data.turns.at(-1).transcript,'');assert.equal((await request('/api/coordinator/reporting')).data.intervalMinutes,30);assert.equal((await request('/api/coordinator/reporting')).data.inventory.complete,true);
 assert.equal((await request('/api/notifications')).data.notifications.some(n=>n.kind==='coordinator_report'),false);
 assert.equal((await request('/api/notifications/'+envelope.notification.id)).status,200);assert.equal((await request('/api/notifications/'+envelope.notification.id,undefined,'token-b')).status,404);
 const db=new DatabaseSync(join(dir,'pocket.sqlite'));t.after(()=>db.close());assert.equal(db.prepare("SELECT COUNT(*) AS n FROM coordinator_reporting WHERE enabled=1").get().n,1);assert.equal(db.prepare('SELECT COUNT(*) AS n FROM session_catchup_presented').get().n,0);
 const r=await fetch('http://127.0.0.1:'+port+'/api/devices/phone_a',{method:'DELETE',headers:{Authorization:'Bearer '+admin}});assert.equal(r.status,200);
 const revokeDeadline=Date.now()+500;while(db.prepare('SELECT enabled FROM coordinator_reporting WHERE device_id=?').get('phone_a').enabled&&Date.now()<revokeDeadline)await new Promise(r=>setTimeout(r,20));
 assert.equal(db.prepare('SELECT enabled FROM coordinator_reporting WHERE device_id=?').get('phone_a').enabled,0);
});
