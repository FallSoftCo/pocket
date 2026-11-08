import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {HollywoodPush} from '../server/hollywood-push.mjs';
const dir=mkdtempSync(join(tmpdir(),'nextcomp-node-inbox-')),file=join(dir,'queue.sqlite');
const authority=randomUUID(),human=randomUUID(),device=randomUUID(),a=randomUUID(),b=randomUUID(),room=randomUUID();
const config={authorityId:authority,principalId:human,bindingId:'synthetic-binding'};
const events=new Map([[a,[]],[b,[]]]);let offline=false;
function add(node,sequence){events.get(node).push({eventId:randomUUID(),roomId:room,sequence,authorityId:authority,principalId:human,revision:1,notify:true,kind:'message',reason:'direct_message',nodeId:node});}
add(a,1);add(b,1);
const calls=[];const client={config,verify:async()=>{},envelope:()=>({}),call:async(path,{query={}}={})=>{
 if(path==='rooms')return {authorityId:authority,nodeId:a,sources:[a,b].map(nodeId=>({authorityId:authority,nodeId,status:'connected'}))};
 const node=query.nodeId;calls.push({path,node,after:query.after});
 if(node===b&&offline)throw Object.assign(Error('synthetic peer outage'),{status:503});
 const rows=events.get(node);assert(rows,'source identity must be explicit');
 const head=rows.at(-1)?.sequence??0;
 return {authorityId:authority,principalId:human,nodeId:node,head,nextCursor:head,events:path==='inbox/pending'||query.after===undefined?[]:rows.filter(h=>h.sequence>query.after)};
}};
let db;
try{
 db=new DatabaseSync(file);db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY)');db.prepare('INSERT INTO devices VALUES(?)').run(device);
 const make=()=>new HollywoodPush({db,gateway:{client:()=>client},push:{enabled:false}});
 let queue=make();assert.deepEqual(await queue.observe(device),[]);
 assert.equal(db.prepare('SELECT count(*) n FROM hollywood_push_deliveries').get().n,0,'initial history is not replayed as new pushes');
 add(a,2);add(b,2);await queue.observe(device);
 assert.equal(db.prepare('SELECT count(*) n FROM hollywood_push_deliveries').get().n,2,'equal independent counters do not suppress either event');
 offline=true;add(a,3);add(b,3);const errors=await queue.observe(device);assert.equal(errors.length,1);
 assert.equal(db.prepare('SELECT cursor FROM hollywood_inbox_node_cursors WHERE node=?').get(a).cursor,3);
 assert.equal(db.prepare('SELECT cursor FROM hollywood_inbox_node_cursors WHERE node=?').get(b).cursor,2,'failed peer cursor remains intact');
 assert.equal(db.prepare('SELECT count(*) n FROM hollywood_push_deliveries').get().n,3,'local update continues during peer failure');
 db.close();db=new DatabaseSync(file);queue=make();offline=false;await queue.observe(device);await queue.observe(device);
 assert.equal(db.prepare('SELECT count(*) n FROM hollywood_push_deliveries').get().n,4,'restart recovers missing peer once without replay');
 assert.equal(db.prepare('SELECT cursor FROM hollywood_inbox_node_cursors WHERE node=?').get(b).cursor,3);
 // Existing local cursor survives the additive per-node migration.
 db.exec('DELETE FROM hollywood_inbox_node_cursors; DELETE FROM hollywood_push_deliveries');
 db.prepare('INSERT INTO hollywood_inbox_cursors(device,binding,cursor,pending_cursor) VALUES(?,?,?,?)').run(device,config.bindingId,2,0);
 await make().observe(device);
 assert.equal(db.prepare('SELECT count(*) n FROM hollywood_push_deliveries').get().n,1);
 assert.equal(JSON.parse(db.prepare('SELECT hint FROM hollywood_push_deliveries').get().hint).nodeId,a);
 console.log(JSON.stringify({passed:true,actualSQLite:true,independentEqualCounters:true,restartPreservesBothCursors:true,peerOutageDoesNotBlockLocal:true,legacyCursorMigration:true,modelsStarted:0,providerPushes:0}));
}finally{db?.close();rmSync(dir,{recursive:true,force:true});}
