import test from 'node:test';
import assert from 'node:assert/strict';
import {changeThreadPermissions} from '../server/permissions.mjs';
import {VoiceController} from '../server/voice-controller.mjs';
import {EventEmitter} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
test('explicit full grant uses settings update when loaded resume ignores overrides; never starts work or changes model',async()=>{
 let profile=':workspace';const calls=[];
 const codex={call:async(method,params)=>{calls.push({method,params});if(method==='thread/settings/update')profile=params.permissions;return{activePermissionProfile:{id:profile},approvalPolicy:'never',sandbox:{type:profile===':danger-full-access'?'dangerFullAccess':'workspaceWrite'}};}};
 const result=await changeThreadPermissions(codex,'existing');
 assert.equal(result.verified,true);assert.equal(result.appliesTo,'subsequent-turns');
 assert.deepEqual(calls.map(x=>x.method),['thread/resume','thread/settings/update','thread/resume']);
 assert.deepEqual(calls[1].params,{threadId:'existing',permissions:':danger-full-access',approvalPolicy:'never'});
 assert.ok(calls.every(x=>!('model'in x.params)&&!('input'in x.params)));
});
test('ignored runtime change fails verification rather than falsely reporting full access',async()=>{
 const codex={call:async()=>({activePermissionProfile:{id:':workspace'},approvalPolicy:'never'})};
 await assert.rejects(changeThreadPermissions(codex,'existing'),/did not apply/);
});
test('parent-owned children are audited without treating unsupported direct mutation as success',async()=>{
 let profile=':danger-full-access';const error=Error('direct app-server input is not allowed for multi-agent v2 sub-agents');
 const codex={call:async method=>{if(method==='thread/settings/update')throw error;return{activePermissionProfile:{id:profile},approvalPolicy:'never',sandbox:{type:profile===':danger-full-access'?'dangerFullAccess':'workspaceWrite'}};}};
 assert.equal((await changeThreadPermissions(codex,'child')).verified,true);
 profile=':workspace';await assert.rejects(changeThreadPermissions(codex,'child'),e=>e===error);
});
test('explicit review preserves native profile choice and verification',async()=>{
 let profile,approval;const codex={call:async(method,p)=>{if(method==='thread/settings/update'){profile=p.permissions;approval=p.approvalPolicy;}return{activePermissionProfile:{id:profile},approvalPolicy:approval,sandbox:{type:profile===':read-only'?'readOnly':'workspaceWrite'}};}};
 assert.equal((await changeThreadPermissions(codex,'coordinator',{full:false,coordinator:true})).permissions,':read-only');
 assert.equal((await changeThreadPermissions(codex,'task',{full:false})).approvalPolicy,'on-request');
});
test('coordinator permission command verifies runtime before changing preference, with truthful partial all-session result',async()=>{
 const db=new DatabaseSync(':memory:');const codex=new EventEmitter();let apply=false;const profiles=new Map([['controller',':read-only'],['work-session',':workspace']]);
 codex.call=async(method,p)=>{
  if(method==='thread/loaded/list')return{data:['controller','work-session','missing-thread']};
  if(p.threadId==='missing-thread')throw Error('thread not found');
  if(method==='thread/settings/update'&&apply)profiles.set(p.threadId,p.permissions);
  const id=profiles.get(p.threadId);return{activePermissionProfile:{id},approvalPolicy:'never',sandbox:{type:id===':danger-full-access'?'dangerFullAccess':id===':read-only'?'readOnly':'workspaceWrite'}};
 };
 const controller=new VoiceController({db,codex});
 db.exec('CREATE TABLE thread_permissions(thread_id TEXT PRIMARY KEY,permissions TEXT NOT NULL)');
 db.prepare("INSERT INTO thread_permissions VALUES('controller','review')").run();
 db.prepare("INSERT INTO voice_sessions(device,thread_id,full) VALUES('phone','controller',0)").run();
 db.prepare("INSERT INTO voice_turns(device,id,hash,state,actions) VALUES('phone','grant-command','fixture','thinking','[]')").run();
 const active={device:'phone',id:'grant-command'};
 await assert.rejects(controller.control(active,{operation:'permissions',arguments:{full:true}}),/did not apply/);
 assert.equal(controller.session('phone').full,0);
 assert.equal(db.prepare("SELECT permissions FROM thread_permissions WHERE thread_id='controller'").get().permissions,'review');
 apply=true;const result=await controller.control(active,{operation:'permissions',arguments:{full:true,all:true}});
 assert.equal(controller.session('phone').full,1);assert.equal(result.verified.length,2);assert.equal(result.blocked.length,1);assert.equal(result.blocked[0].threadId,'missing-thread');assert.equal(result.appliesTo,'subsequent-turns');
 assert.equal(db.prepare("SELECT permissions FROM thread_permissions WHERE thread_id='work-session'").get().permissions,'full');
 assert.equal(db.prepare("SELECT permissions FROM thread_permissions WHERE thread_id='missing-thread'").get(),undefined);
 assert.equal(controller.get('phone','grant-command').actions.length,1);db.close();
});
