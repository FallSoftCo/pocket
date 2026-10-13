import test from 'node:test';
import assert from 'node:assert/strict';
import {Losangelex,teamRequest} from '../server/losangelex.mjs';

const room='11111111-1111-4111-8111-111111111111';
const actor='22222222-2222-4222-8222-222222222222';
test('room routes retain explicit team and reject transport/body injection',()=>{
  assert.deepEqual(teamRequest('GET','teams/research/rooms',{limit:'100'}),{path:'teams/research/rooms',query:{limit:'100'}});
  assert.equal(teamRequest('GET',`teams/research/rooms/${room}`,{}).path,`teams/research/rooms/${room}`);
  const path=`teams/research/rooms/${room}/members/${actor}`;
  assert.equal(teamRequest('PUT',path,{},{}).method,'PUT');
  for(const body of [{url:'https://example.com'},{targets:['coordinator']},[]]){
    assert.throws(()=>teamRequest('PUT',path,{},body));
  }
  assert.throws(()=>teamRequest('GET',`teams/research/rooms/${room}`,{token:'injected'}));
  assert.throws(()=>teamRequest('PUT',path.replace(actor,'coordinator'),{},{}));
});
test('desired membership forwards PUT with server credential and no model command',async()=>{
  let observed;
  const client=new Losangelex({url:'http://127.0.0.1:18767',token:'fixture-only'},{fetchImpl:async(url,options)=>{
    observed={url,options};return new Response(JSON.stringify({id:room,members:[{id:actor}]}),{status:200});
  }});
  const command=teamRequest('PUT',`teams/research/rooms/${room}/members/${actor}`,{},{});
  const result=await client.call(command.path,command);
  assert.equal(result.id,room);
  assert.equal(observed.options.method,'PUT');
  assert.equal(observed.options.body,'{}');
  assert.equal(observed.options.headers.Authorization,'Bearer fixture-only');
  assert.equal(observed.url.pathname,`/hollywood/v2/teams/research/rooms/${room}/members/${actor}`);
  assert.equal(observed.options.redirect,'manual');
});

test('room creation forwards only metadata and permits exact recovery lookup',()=>{
  const body={task:'room-stable-id',title:'Product engineering',purpose:'Implementation and delivery'};
  assert.deepEqual(teamRequest('POST','teams/enterprise/rooms',{},body),{path:'teams/enterprise/rooms',body});
  assert.deepEqual(teamRequest('GET','teams/enterprise/rooms',{task:body.task}),{path:'teams/enterprise/rooms',query:{task:body.task}});
  for(const bad of [{...body,targets:['global-steward']},{...body,commandId:'pretend-work'},{...body,url:'https://injected.example'},{...body,title:''},{...body,task:'wrong/path'}])assert.throws(()=>teamRequest('POST','teams/enterprise/rooms',{},bad));
});
