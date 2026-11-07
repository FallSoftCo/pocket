import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';
import {InternalSessions} from '../server/internal-sessions.mjs';import {CatalogContext} from '../server/catalog-context.mjs';
test('actual owned directory and durable identity hide workers across restart without hiding user work',()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE pocket_discovered_threads(metadata TEXT)');
 db.prepare('INSERT INTO pocket_discovered_threads VALUES(?)').run(JSON.stringify({id:'old',cwd:'/private/runtime/immersion'}));
 const registry=new InternalSessions(db,[{cwd:'/private/runtime/immersion',kind:'immersion'}]);
 assert.equal(registry.owns('old'),true);assert.equal(registry.owns('new',{id:'new',cwd:'/private/runtime/immersion'}),true);
 assert.equal(registry.owns('user',{id:'user',cwd:'/private/runtime/immersion-project',name:'immersion'}),false);
 registry.register('voice-worker','voice');assert.equal(new InternalSessions(db).owns('voice-worker'),true);
 assert.equal(new InternalSessions(db).owns('new'),true);db.close();
});
test('empty fresh stores work and informative blockers do not invent runtime activity or survive new work',()=>{
 const db=new DatabaseSync(':memory:');new InternalSessions(db);let now=1_800_000_000_000;const c=new CatalogContext(db,()=>now);
 c.put('grant','Awaiting reference name, email and permission',true);
 const thread={id:'grant',status:{type:'idle'},activityAt:now-1000};assert.equal(c.get(thread).needsInput,true);
 assert.deepEqual(c.get({...thread,status:{type:'active'}}),{});assert.deepEqual(c.get({...thread,activityAt:now+1}),{});
 assert.equal(thread.status.type,'idle');db.close();
});
test('retired room provenance is excluded from fresh, cached, searched and watched stock discovery',async()=>{
 const {SessionDiscovery}=await import('../server/session-discovery.mjs');
 const db=new DatabaseSync(':memory:');db.exec("CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0,seen_at INTEGER NOT NULL);CREATE TABLE watches(thread_id TEXT,enabled INTEGER)");
 const legacy={id:'legacy',originator:'lax-host-agent',source:'vscode',preview:'Consent question',status:{type:'notLoaded'}};
 const user={id:'user',originator:'codex_cli_rs',source:'cli',preview:'Work on lax-host-agent',cwd:'/projects/lax-host-agent',status:{type:'idle'}};
 db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?,0,0)').run(legacy.id,JSON.stringify(legacy));
 db.prepare('INSERT INTO watches VALUES(?,1)').run(legacy.id);
 const registry=new InternalSessions(db),calls=[];
 const d=new SessionDiscovery({db,hidden:(id,t)=>registry.owns(id,t),codex:{call:async(m,p)=>{calls.push(m);assert.equal(m,'thread/list');return {data:[legacy,user],nextCursor:null};}}});
 assert.deepEqual(d.snapshot(false).data,[]);
 for(const options of [{},{searchTerm:'Consent'},{cursor:'next'},{archived:true}])assert.deepEqual((await d.list(options)).data.map(t=>t.id),['user']);
 assert.equal(new InternalSessions(db).owns(legacy.id),true);
 assert.equal(registry.owns('future',{id:'future',originator:'lax-host-agent'}),true);
 assert.equal(registry.owns(user.id,user),false);
 assert.ok(calls.length>=4);db.close();
});
