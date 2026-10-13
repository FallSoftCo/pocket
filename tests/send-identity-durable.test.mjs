import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {SendIdentity} from '../server/send-identity.mjs';
import {LiveTimeline,timelinePage} from '../server/timeline.mjs';
import {ThreadHistory} from '../server/history.mjs';
const user=(id='item',clientId=null)=>({id,type:'userMessage',clientId,content:[{type:'text',text:'same words'}]});
const snapshot=(item)=>({id:'thread',turns:[{id:'turn',status:'completed',items:[item]}]});
test('provider identity survives database reopen and metadata-poor history',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-identity-'));let db;
 try{
  db=new DatabaseSync(join(dir,'evidence.sqlite'));let identity=new SendIdentity(db);
  const live=new LiveTimeline();
  live.ingest(identity.event({method:'item/started',params:{threadId:'thread',turnId:'turn',item:user('item','send')}}));
  db.close();db=new DatabaseSync(join(dir,'evidence.sqlite'));identity=new SendIdentity(db);
  const history=new ThreadHistory({call:async()=>({thread:snapshot(user())})},{identity});
  const thread=await history.read({id:'thread'});
  assert.equal(timelinePage(thread).rows.find(r=>r.kind==='user').clientId,'send');
  assert.equal(live.ingest(identity.event({method:'item/completed',params:{threadId:'thread',turnId:'turn',item:user()}})).row.clientId,'send');
 }finally{db?.close();rmSync(dir,{recursive:true,force:true});}
});
test('identical inputs remain distinct; thread, turn and environment isolation',()=>{
 const a=new DatabaseSync(':memory:'),b=new DatabaseSync(':memory:');
 try{
  const x=new SendIdentity(a),y=new SendIdentity(b);
  x.item('thread','turn',user('first','send1'));x.item('thread','turn',user('second','send2'));
  assert.equal(x.item('thread','turn',user('first')).clientId,'send1');
  assert.equal(x.item('thread','turn',user('second')).clientId,'send2');
  assert.equal(x.item('other','turn',user('first')).clientId,null);
  assert.equal(x.item('thread','other',user('first')).clientId,null);
  assert.equal(y.item('thread','turn',user('first')).clientId,null);
  assert.equal(x.item('thread','turn',user('unproven')).clientId,null);
 }finally{a.close();b.close();}
});
test('conflicting claims never enrich an unproven row',()=>{
 const db=new DatabaseSync(':memory:');try{
 const x=new SendIdentity(db);x.item('thread','turn',user('item','a'));x.item('thread','turn',user('item','b'));
 assert.equal(x.item('thread','turn',user()).clientId,null);
 }finally{db.close();}
});
test('compact history and alias field retain only exact provider evidence',()=>{
 const db=new DatabaseSync(':memory:');try{
 const x=new SendIdentity(db);
 x.event({method:'turn/started',params:{threadId:'thread',turn:{id:'turn',items:[{...user(),clientId:null,clientUserMessageId:'send'}]}}});
 const compact={id:'item',type:'userMessage',_pocketRow:{kind:'user',id:'turn/item',turnId:'turn',text:'same words'}};
 assert.equal(x.item('thread','turn',compact)._pocketRow.clientId,'send');
 assert.equal(x.item('thread','turn',{...compact,id:'other'})._pocketRow.clientId,undefined);
 assert.equal(x.item('thread','turn',{...user(),type:'agentMessage'}).clientId,null);
 }finally{db.close();}
});
