import test from 'node:test';
import assert from 'node:assert/strict';
import {RoomClient} from '../web/room-client.mjs';
const id=n=>'cccccccc-cccc-4ccc-8ccc-'+String(n).padStart(12,'0');
const binding={bindingId:'a'.repeat(64),installationId:id(1),authorityId:id(2),principalId:id(3),defaultRoomId:id(4)};
const room={id:id(4),authority:id(2),name:'Original room'};
const event=n=>({id:id(n),seq:n,authority:id(2),room:id(4),author:id(5),body:'Answer '+n,audience:'room'});
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const turn=()=>new Promise(r=>setImmediate(r));
test('discovery publishes healthy ingress and opens head while unrelated identity remains held; one first-wave request per source',async t=>{
 const held=deferred(),calls=[],snapshots=[];
 const client=new RoomClient(async(path,_,options)=>{
  calls.push(path);if(path.includes('/gpu/')&&path.endsWith('/identity'))return held.promise;
  if(path.endsWith('/identity'))return binding;
  assert.equal(options.headers['X-Hollywood-Binding'],binding.bindingId);
  if(path.endsWith('/rooms'))return {authorityId:id(2),rooms:[room],binding};
  return {authorityId:id(2),roomId:id(4),view:'all',events:[event(10)],nextCursor:10,olderCursor:null,binding};
 });t.after(()=>client.discovery.controller.abort());
 const roster=[{id:'current',name:'CPU'},{id:'gpu',name:'GPU'}];
 const ready=client.discover(roster,{onUpdate:s=>snapshots.push(s)});
 assert.equal(client.discover(roster),ready,'An in-flight discovery is singleflight');
 const [entry]=await ready;assert.ok(entry);
 assert.equal(client.discovery.finished,false,'Unrelated source is still pending');
 assert.equal((await client.read(entry.id)).rows[0].text,'Answer 10');
 assert.equal(calls.filter(p=>p.includes('/gpu/')&&p.endsWith('/identity')).length,1);
 assert.ok(snapshots.some(s=>s.length===1));
 held.resolve({...binding,bindingId:'b'.repeat(64)});await client.discovery.done;
});
test('read-only failover keeps canonical history, selected physical drafts and captured receipts on original route',async()=>{
 let primaryFails=false,seq=10;const calls=[];
 const api=async(path)=>{calls.push(path);const remote=path.includes('/gpu/'),b=remote?{...binding,bindingId:'b'.repeat(64)}:binding;
  if(path.endsWith('/identity'))return b;
  if(path.endsWith('/rooms'))return {authorityId:id(2),rooms:[room],binding:b};
  if(primaryFails&&!remote)throw Object.assign(Error('Original gateway unavailable'),{status:503});
  return {authorityId:id(2),roomId:id(4),view:'all',events:[event(seq)],nextCursor:seq,olderCursor:9,binding:b};
 };
 const client=new RoomClient(api);await client.discover([{id:'current',name:'CPU'},{id:'gpu',name:'GPU'}]);await client.discovery.done;
 const entry=[...client.entries.values()][0],captured=structuredClone(client.route(entry.id));
 await client.read(entry.id);primaryFails=true;seq=11;
 const page=await client.read(entry.id);assert.deepEqual(page.rows.map(r=>r.canonicalSeq),[10,11]);assert.equal(page.before,9);
 assert.equal(client.entry(entry.id).selected,'current');assert.equal(client.entry(entry.id).readEnvironment,'gpu');
 assert.throws(()=>client.route(entry.id),/Original room gateway/);
 const at=calls.length;await assert.rejects(client.call(captured,'commands/original-operation'),/Original gateway unavailable/);
 assert.deepEqual(calls.slice(at),['/api/hollywood/v3/commands/original-operation'],'Original receipts never switch physical gateways');
});
test('a cancelled old discovery cannot overwrite the newer roster even when transport ignores abort',async()=>{
 const held=deferred();let identities=0;
 const newer={...binding,bindingId:'b'.repeat(64),principalId:id(9)};
 const client=new RoomClient(async path=>{
  if(path.endsWith('/identity'))return ++identities===1?held.promise:newer;
  return {authorityId:id(2),rooms:[room],binding:newer};
 });
 const first=client.discover([{id:'current',name:'Old roster'}]);await turn();
 const [entry]=await client.discover([{id:'current',name:'New roster'}]);await client.discovery.done;
 assert.equal(entry.binding.principalId,id(9));held.resolve(binding);await first;await turn();
 assert.ok([...client.entries.values()].every(e=>e.binding.principalId===id(9)));
});
test('one federated catalog suffices; bounded first wave and cheap cached identity replace repeated fleet scans',async()=>{
 let now=10000;const calls=[];
 const client=new RoomClient(async path=>{calls.push(path);if(path.endsWith('/identity'))return binding;return {authorityId:id(2),rooms:[room],binding};},{clock:()=>now});
 const sources=Array.from({length:128},(_,n)=>({id:'src'+n,name:'Source '+n}));
 await client.discover(sources);await client.discovery.done;
 assert.equal(calls.filter(p=>p.endsWith('/identity')).length,3);
 const old=calls.length;await client.discover(sources);assert.equal(calls.length,old);
 now+=16000;await client.discover(sources);await client.discovery.done;
 assert.equal(calls.filter(p=>p.endsWith('/identity')).length,3,'Fresh identity is reused, bound room replies still reverified');
 now+=61000;await client.discover(sources);await client.discovery.done;
 assert.equal(calls.filter(p=>p.endsWith('/identity')).length,6);
});


test('fresh fallback catalog clears cached setup failure without retargeting the selected write route',async()=>{
 let phase=0;const held=deferred();
 const client=new RoomClient(async path=>{
  const remote=path.includes('/gpu/'),b=remote?{...binding,bindingId:'b'.repeat(64)}:binding;
  if(path.endsWith('/identity'))return b;
  if(phase===1&&!remote)return held.promise;
  return {authorityId:id(2),rooms:[{...room,roomAgents:{marker:phase===0?'blocked':'ready'}}],binding:b};
 });
 const sources=[{id:'current',name:'CPU'},{id:'gpu',name:'GPU'}];
 const [entry]=await client.discover(sources);await client.discovery.done;
 entry.selected='current';entry.readEnvironment='current';
 const captured=structuredClone(client.route(entry.id));
 phase=1;await client.discover(sources,{force:true});await turn();await turn();
 assert.equal(client.entry(entry.id).room.roomAgents.marker,'ready');
 assert.equal(client.entry(entry.id).selected,'current');
 assert.equal(client.route(entry.id).binding.bindingId,captured.binding.bindingId);
 held.resolve({authorityId:id(2),rooms:[{...room,roomAgents:{marker:'ready'}}],binding});
 await client.discovery.done;
});
