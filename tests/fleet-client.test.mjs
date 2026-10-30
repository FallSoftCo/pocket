import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fleetDescriptor,fleetSession,fleetWorkIdentity} from '../web/fleet.mjs';
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const descriptor=()=>({version:1,id:id(1),accountId:id(2),nodeId:id(3),sessionPath:'/api/fleet/session',members:[
 {id:id(3),name:'CPU',origin:'https://cpu.example.test:19426'},
 {id:id(4),name:'GPU',origin:'https://gpu.example.test:19426'}]});
test('authenticated fleet metadata cannot redirect opaque origin credentials or alias distinct hosts',()=>{
 const accepted=fleetDescriptor(descriptor());assert.equal(accepted.members.length,2);
 for(const patch of [
  {members:[...descriptor().members,descriptor().members[0]]},
  {members:[{...descriptor().members[0],origin:'https://owner:password@cpu.example.test'}]},
  {members:[{...descriptor().members[0],origin:'http://cpu.example.test'}]},
  {members:[{...descriptor().members[0],origin:'https://cpu.example.test/#other'}]},
  {sessionPath:'https://other.example.test/api/pair'},
 ])assert.throws(()=>fleetDescriptor({...descriptor(),...patch}));
 assert.equal(Object.hasOwn(accepted.members[0],'token'),false);
});
test('session renewal leaves logical room identity stable; account/host scopes stay separate',()=>{
 const d=fleetDescriptor(descriptor()),now=100000;
 const login={version:1,fleetId:d.id,accountId:d.accountId,deviceId:id(5),token:'a'.repeat(64),expiresAt:now+60000};
 const first=fleetSession(login,d,now),renewed=fleetSession({...login,token:'b'.repeat(64),expiresAt:now+120000},d,now);
 assert.notEqual(first.token,renewed.token);
 const room={fleetId:d.id,accountId:d.accountId,viewerId:id(6),kind:'room',namespace:id(7),id:id(8)};
 const key=fleetWorkIdentity(room);assert.equal(key,fleetWorkIdentity({...room}));
 assert.notEqual(key,fleetWorkIdentity({...room,accountId:id(9)}));
 const session={...room,kind:'session',namespace:id(3)};
 assert.notEqual(fleetWorkIdentity(session),fleetWorkIdentity({...session,namespace:id(4)}));
 assert.throws(()=>fleetSession({...login,accountId:id(9)},d,now));
 assert.throws(()=>fleetSession({...login,expiresAt:now},d,now));
 assert.equal(String(first).includes(first.token),false);
 assert.equal(JSON.stringify(first),'{}');
});
