import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {mkdtempSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {DatabaseSync} from 'node:sqlite';
import {Losangelex,loadLosangelex,mountLosangelex,teamRequest} from '../server/losangelex.mjs';
import {TeamAttention} from '../server/losangelex-attention.mjs';

test('owner registration isolates endpoints and credentials from device commands',()=>{
  const dir=mkdtempSync(join(tmpdir(),'team-config-')),token=join(dir,'token');writeFileSync(token,'private-token');
  assert.equal(loadLosangelex(dir),null);
  for(const url of ['http://attacker.test','https://user:password@host.test','https://host.test/path','https://host.test?token=wrong']){
    writeFileSync(join(dir,'losangelex.json'),JSON.stringify({url,tokenFile:token}));assert.deepEqual(loadLosangelex(dir),{invalid:true});
  }
  writeFileSync(join(dir,'losangelex.json'),JSON.stringify({url:'http://127.0.0.1:18766',tokenFile:token}));
  const config=loadLosangelex(dir);assert.equal(config.url,'http://127.0.0.1:18766');assert.equal(config.token,'private-token');assert.match(config.environmentId,/^[0-9a-f]{20}$/);
});

test('adapter retains origin, peer target and command ID, rejects redirects and unknown operations',async()=>{
  const calls=[];
  const client=new Losangelex({url:'http://127.0.0.1:18766',token:'secret',environmentId:'owner'},{fetchImpl:async(url,options)=>{calls.push({url:String(url),options});return new Response(JSON.stringify({id:7}),{status:202});}});
  const body={commandId:'same-id',task:'work',targets:['maya'],visibility:'direct',replyTo:5,body:'Follow up'};
  const command=teamRequest('POST','/teams/default/messages',{},body);
  await client.call(command.path,command);await client.call(command.path,command);
  assert.deepEqual(calls[0],calls[1]);assert.equal(calls[0].url,'http://127.0.0.1:18766/hollywood/v2/teams/default/messages');
  assert.deepEqual(JSON.parse(calls[0].options.body),body);assert.equal(calls[0].options.redirect,'manual');
  for(const [method,path,query,payload] of [['POST','/teams/default/update',{},{}],['POST','/teams/default/messages',{}, {...body,url:'http://attacker.test'}],['GET','/teams/default/history',{url:'http://attacker.test'}],['GET','/../health',{}],['POST','/teams/default/limits/reset',{},{}]])assert.throws(()=>teamRequest(method,path,query,payload));
  const redirected=new Losangelex(client.config,{fetchImpl:async()=>new Response(null,{status:302,headers:{location:'http://attacker.test'}})});
  await assert.rejects(redirected.call('teams'),/redirected/);
  const uncertain=new Losangelex(client.config,{fetchImpl:async()=>{throw Error('secret transport detail');}});
  await assert.rejects(uncertain.call('teams/default/messages',{body}),/same saved command/);
  assert.equal((await uncertain.status()).available,false);assert.ok(!JSON.stringify(await uncertain.status()).includes('secret'));
});

test('paired authorization surrounds both providers and Losangelex failures leave Codex available',async t=>{
  const app=express();app.use(express.json());
  app.use('/api',(req,res,next)=>req.get('Authorization')==='Bearer paired'?next():res.status(401).json({error:'Pair first'}));
  let calls=0;const client=new Losangelex(null);client.call=async()=>{calls++;return {teams:[]};};
  mountLosangelex(app,{client,codex:{ready:true}});
  app.use((error,req,res,next)=>res.status(error.status||500).json({error:error.message}));
  const server=app.listen(0,'127.0.0.1');t.after(()=>server.close());await new Promise(r=>server.once('listening',r));
  const origin=`http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(origin+'/api/losangelex/teams')).status,401);assert.equal(calls,0);
  const statuses=await (await fetch(origin+'/api/backends',{headers:{Authorization:'Bearer paired'}})).json();
  assert.equal(statuses.backends[0].available,true);assert.equal(statuses.backends[1].configured,false);
  assert.equal((await fetch(origin+'/api/losangelex/teams',{headers:{Authorization:'Bearer paired'}})).status,200);assert.equal(calls,1);
});

test('attention outbox deduplicates, preserves private origins, survives restart and does not resolve on outage',async()=>{
  const db=new DatabaseSync(':memory:');let published=0;const resolved=[];
  let page={data:[{id:31,team_id:'one',task_id:'private-task',author:'maya',body:'private detail',visibility:'direct:maya'}],next_cursor:null};
  const client={config:{environmentId:'first'},call:async(path)=>path.includes('/messages/')?{event:{id:31,kind:'attention'},attentionState:'open'}:page};
  const settings={client,db,publish:(thread,commit)=>{assert.match(thread,/^lx-[a-f0-9]{24}$/);commit({id:++published});},resolve:(where,id)=>resolved.push(id)};
  let observer=new TeamAttention(settings);await observer.poll();await observer.poll();assert.equal(published,1);
  const target=await observer.target(db.prepare('SELECT thread_id FROM losangelex_notifications').get().thread_id);
  assert.equal(target.team,'one');assert.equal(target.task,'private-task');
  observer=new TeamAttention(settings);await observer.poll();assert.equal(published,1);
  client.call=async()=>{throw Error('offline');};await assert.rejects(observer.poll());assert.deepEqual(resolved,[]);
  client.call=async()=>({data:[],next_cursor:null});await observer.poll();assert.deepEqual(resolved,[1]);
  client.config.environmentId='second';await assert.rejects(observer.target(target.thread_id),/different backend/);
  db.close();
});

test('incomplete paginated attention scans never clear already-visible questions',async()=>{
  const db=new DatabaseSync(':memory:');const resolved=[];let requests=0;
  const client={config:{environmentId:'test'},call:async()=>{if(++requests===2)throw Error('page lost');return {data:[{id:1,team_id:'one',task:'task',author:'maya'}],next_cursor:1};}};
  const observer=new TeamAttention({client,db,publish:(_,commit)=>commit({id:5}),resolve:(...x)=>resolved.push(x)});
  await assert.rejects(observer.poll());assert.deepEqual(resolved,[]);assert.equal(db.prepare('SELECT count(*) AS n FROM losangelex_notifications').get().n,1);db.close();
});
