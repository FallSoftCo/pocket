import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {RoomProgress} from '../server/room-progress.mjs';
test('Global updates are durable, scoped and deduplicated across observer restart',async()=>{
 const db=new DatabaseSync(':memory:');const published=[],calls=[];let seeded=false;
 const events=[
  {id:11,kind:'message',task:'global',author:'you',visibility:'room',body:'Human input'},
  {id:12,kind:'message',task:'other',author:'peer',visibility:'room',body:'Other room'},
  {id:13,kind:'message',task:'global',author:'steward',visibility:'room',body:'Verified delivery'},
  {id:14,kind:'message',task:'global',author:'steward',visibility:'direct:steward',body:'Private reply'},
  {id:15,kind:'artifact',task:'global',author:'steward',visibility:'room',body:'Command details'}];
 const client={config:{environmentId:'host-A'},async call(path,options={}){calls.push({path,options});
  if(path.endsWith('/overview')){seeded=true;return {cursor:10};}
  if(path.endsWith('/rooms'))return {rooms:[{task:'global',title:'Global'}]};
  return {data:events.filter(e=>e.id>options.query.after)};
 }};
 const publish=(thread,title,event,onPersist)=>{db.exec('BEGIN IMMEDIATE');try{onPersist({id:1});db.exec('COMMIT');published.push({thread,title,event});}catch(e){db.exec('ROLLBACK');throw e;}};
 try{
  const args={db,client,scopes:[{team:'enterprise',task:'global'}],publish};
  const observer=new RoomProgress(args);await observer.poll();assert.equal(seeded,true);assert.equal(published.length,0);
  await observer.poll();assert.equal(published.length,1);assert.equal(published[0].title,'Global');assert.equal(published[0].event.id,13);
  await new RoomProgress(args).poll();assert.equal(published.length,1);
  assert.ok(calls.every(c=>c.options.body===undefined));
  assert.equal(db.prepare('SELECT cursor FROM room_progress_cursor').get().cursor,15);
 }finally{db.close();}
});
test('notification persistence failure retains cursor and invalid scopes cannot inject endpoints',async()=>{
 const db=new DatabaseSync(':memory:');let reject=true,publications=0;
 const event={id:2,kind:'message',task:'global',author:'steward',visibility:'room',body:'Result'};
 const client={config:{environmentId:'A'},async call(path,options={}){if(path.endsWith('/overview'))return {cursor:1};if(path.endsWith('/rooms'))return {rooms:[{task:'global',title:'Global'}]};return {data:options.query.after<2?[event]:[]};}};
 try{
  const observer=new RoomProgress({db,client,scopes:[{team:'enterprise',task:'global'}],publish:(t,n,e,persist)=>{if(reject)throw Error('Storage unavailable');persist({id:1});publications++;}});
  await observer.poll();await assert.rejects(observer.poll(),/Storage unavailable/);assert.equal(db.prepare('SELECT cursor FROM room_progress_cursor').get().cursor,1);
  reject=false;await observer.poll();await observer.poll();assert.equal(publications,1);
  assert.throws(()=>new RoomProgress({db,client,scopes:[{team:'enterprise',task:'global',url:'https://injected.example'}]}),/Invalid/);
 }finally{db.close();}
});
