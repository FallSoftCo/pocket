import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {generateKeyPairSync,randomUUID,randomBytes} from 'node:crypto';
import {openStore,hash} from '../server/store.mjs';
import {LocalFleetAccess,enrollmentKey} from '../server/fleet-enrollment.mjs';
test('independent local enrollment, lost ACK, restart, deny-only partition catch-up and no resurrection',async()=>{
 const root=mkdtempSync(resolve(tmpdir(),'nextcomp-enroll-')),fleetId=randomUUID(),accountId=randomUUID(),instances=[],stores=[],online=new Set([0,1,2]);let networkCalls=0;
 const members=[0,1,2].map(i=>{const k=generateKeyPairSync('ed25519'),id=randomUUID();writeFileSync(resolve(root,id),k.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});return {id,name:'fixture'+i,origin:'https://fixture'+i+'.invalid',publicKey:k.publicKey.export({type:'spki',format:'pem'})};});
 const configs=members.map(m=>({version:1,fleetId,accountId,nodeId:m.id,privateKeyFile:resolve(root,m.id),members}));
 const keys=members.map((m,i)=>{const data=resolve(root,'node'+i);mkdirSync(data);stores.push(openStore(data));return enrollmentKey(data);});
 function make(i){return new LocalFleetAccess({db:stores[i].db,config:configs[i],credentialKey:keys[i],fetcher:async(url,options)=>{
  networkCalls++;const peer=members.findIndex(m=>new URL(url).origin===m.origin);assert.ok(peer>=0);if(!online.has(peer))throw Error('partition');const target=instances[peer],path=new URL(url).pathname;
  await target.authorizeNode(options.headers.Authorization.slice(7),{method:'GET',path,scope:'revocation:read'});return new Response(JSON.stringify(target.revocationPage(Number(path.split('/').at(-1)))));
 }});}
 try{
  instances.push(make(0),make(1),make(2));const [a,b,c]=instances,id=randomUUID(),legacy=randomBytes(32).toString('hex');stores[0].db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(id,'Original phone',hash(legacy),100);
  const portable=await a.session({id,name:'Original phone'}),auth=await b.authenticate(portable.token),op='original-enrollment-r01';
  const enrolled=await b.enroll(auth.device,auth.principal,op),recovered=await b.enrollment(auth.device,auth.principal,op);assert.deepEqual(recovered,enrolled);
  assert.deepEqual(await b.enroll(auth.device,auth.principal,op),enrolled);assert.equal(stores[1].db.prepare('SELECT count(*) n FROM fleet_credentials').get().n,1);assert.equal(stores[1].db.prepare('SELECT count(*) n FROM fleet_enrollments').get().n,1);
  const simultaneous=await Promise.all([b.enroll(auth.device,auth.principal,op),b.enroll(auth.device,auth.principal,op)]);assert.deepEqual(simultaneous,[enrolled,enrolled]);
  const home=await a.enroll({id},null,'home-enrollment-r01');assert.equal(home.deviceId,id);assert.equal(home.ownerDeviceId,enrolled.ownerDeviceId);assert.equal(stores[0].db.prepare('SELECT token_hash FROM devices WHERE id=?').get(id).token_hash,hash(legacy));
  online.delete(0);networkCalls=0;const local=b.opaqueIdentity(enrolled.token);assert.equal(local.device.id,enrolled.deviceId);
  const refresh=await b.session(local.device,local.principal);await b.authorizeAlias(local.device.id);assert.equal(networkCalls,0);
  instances[1]=make(1);assert.deepEqual(await instances[1].enrollment(local.device,local.principal,op),enrolled);
  const third=await c.authenticate(refresh.token),thirdEnrollment=await c.enroll(third.device,third.principal,'third-enrollment-r01');assert.equal(thirdEnrollment.ownerDeviceId,enrolled.ownerDeviceId);
  const other=randomUUID();stores[0].db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(other,'Other phone',hash('other'),101);
  const another=await a.session({id:other}),anotherAuth=await instances[1].authenticate(another.token);await assert.rejects(instances[1].enrollment(anotherAuth.device,anotherAuth.principal,op));
  const proof=await a.nodeProof(members[1].id,{method:'GET',path:'/fleet/v1/revocations/0',scope:'revocation:read'});await assert.rejects(instances[1].authenticate(proof));await assert.rejects(instances[1].session({id:'owner'}));
  assert.equal(instances[1].deviceActive(local.device.id),true);
  const revoked=await instances[1].revoke(local.device,local.principal,'original-revocation-r01');assert.equal(revoked.propagation,'eventual');assert.throws(()=>instances[1].opaqueIdentity(enrolled.token));assert.equal(instances[1].deviceActive(local.device.id),false);
  assert.deepEqual(await instances[1].revocationReceipt(enrolled.token,'original-revocation-r01'),revoked);
  // A partition does not pretend to be instantaneous fleet-wide revocation.
  assert.ok(a.opaqueIdentity(home.token));online.delete(1);await a.syncRevocations();assert.ok(a.opaqueIdentity(home.token));
  online.add(1);await a.syncRevocations();assert.throws(()=>a.opaqueIdentity(home.token));assert.throws(()=>a.authorizeLocal({id}));
  // Durable denial relays through a member; the original issuer and revoker may both be unavailable.
  online.delete(1);online.add(0);await c.syncRevocations();assert.throws(()=>c.opaqueIdentity(thirdEnrollment.token));
  instances[2]=make(2);assert.throws(()=>instances[2].opaqueIdentity(thirdEnrollment.token));await assert.rejects(instances[2].authenticate(portable.token));
  assert.throws(()=>instances[2].importPage(members[0],{...a.revocationPage(0),streamId:randomUUID()},stores[2].db.prepare('SELECT * FROM fleet_revocation_cursors WHERE node_id=?').get(members[0].id)));
  assert.throws(()=>a.revocationPage(999));
  await assert.rejects(instances[1].enrollment(local.device,local.principal,op));assert.equal(stores[1].db.prepare('SELECT count(*) n FROM fleet_enrollments WHERE operation_id=?').get(op).n,1);
  assert.throws(()=>new LocalFleetAccess({db:stores[1].db,credentialKey:randomBytes(32),config:configs[1]}));
  assert.throws(()=>new LocalFleetAccess({db:stores[1].db,credentialKey:keys[1],config:{...configs[1],accountId:randomUUID()}}));
 }finally{for(const s of stores)s.db.close();rmSync(root,{recursive:true,force:true});}
});
