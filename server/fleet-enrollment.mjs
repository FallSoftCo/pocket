/** Locally authenticated fleet devices. Replication only imports durable DENIALS.
 * No request to the original gateway is on the enrolled read/action path.
 */
import {openSync,closeSync,writeSync,fsyncSync,constants} from 'node:fs';
import {resolve} from 'node:path';
import {randomBytes,randomUUID,createHash,createHmac} from 'node:crypto';
import {FleetAccess,protectedFile} from './fleet.mjs';
const uuid=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const op=/^[A-Za-z0-9_-]{8,100}$/;
const fail=()=>Object.assign(Error('Fleet device authorization is unavailable or revoked.'),{status:401});
const need=v=>{if(!v)throw fail();};
const hash=s=>createHash('sha256').update(s).digest('hex');
export function enrollmentKey(dir){
 const path=resolve(dir,'fleet-credential-key');let fd;
 try{fd=openSync(path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);const b=Buffer.from(randomBytes(32).toString('hex')+'\n');let n=0;while(n<b.length)n+=writeSync(fd,b,n,b.length-n);fsyncSync(fd);}
 catch(e){if(e.code!=='EEXIST')throw e;}finally{if(fd!==undefined)closeSync(fd);}
 const d=openSync(dir,constants.O_RDONLY|constants.O_DIRECTORY);try{fsyncSync(d);}finally{closeSync(d);}
 const raw=protectedFile(path).trim();need(/^[a-f0-9]{64}$/.test(raw));return Buffer.from(raw,'hex');
}
export class LocalFleetAccess extends FleetAccess{
 constructor({credentialKey,...options}){
  super(options);need(Buffer.isBuffer(credentialKey)&&credentialKey.length===32);this.credentialKey=credentialKey;this.syncing=null;this.peerState=new Map();
  this.db.exec(`CREATE TABLE IF NOT EXISTS fleet_realm(id INTEGER PRIMARY KEY CHECK(id=1),fleet_id TEXT NOT NULL,account_id TEXT NOT NULL,node_id TEXT NOT NULL,stream_id TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_presence(local_id TEXT PRIMARY KEY,owner_device_id TEXT UNIQUE NOT NULL,home_node TEXT NOT NULL,generation TEXT NOT NULL,fleet_id TEXT NOT NULL,account_id TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_credentials(token_hash TEXT PRIMARY KEY,local_id TEXT NOT NULL,owner_device_id TEXT UNIQUE NOT NULL,generation TEXT NOT NULL,fleet_id TEXT NOT NULL,account_id TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_enrollments(operation_id TEXT PRIMARY KEY,local_id TEXT NOT NULL,owner_device_id TEXT NOT NULL,generation TEXT NOT NULL,created_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_tombstones(owner_device_id TEXT NOT NULL,generation TEXT NOT NULL,home_node TEXT NOT NULL,event_id TEXT UNIQUE NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner_device_id,generation));
   CREATE TABLE IF NOT EXISTS fleet_revocations(seq INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT UNIQUE NOT NULL,owner_device_id TEXT NOT NULL,generation TEXT NOT NULL,home_node TEXT NOT NULL,created_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_revocation_cursors(node_id TEXT PRIMARY KEY,stream_id TEXT NOT NULL,seq INTEGER NOT NULL,updated_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_revoke_receipts(operation_id TEXT PRIMARY KEY,owner_device_id TEXT NOT NULL,generation TEXT NOT NULL,result TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_credential_key(id INTEGER PRIMARY KEY CHECK(id=1),fingerprint TEXT NOT NULL);`);
  this.db.prepare('INSERT OR IGNORE INTO fleet_credential_key VALUES(1,?)').run(hash(credentialKey));need(this.db.prepare('SELECT fingerprint FROM fleet_credential_key WHERE id=1').get().fingerprint===hash(credentialKey));
  const c=this.config();this.db.prepare('INSERT OR IGNORE INTO fleet_realm VALUES(1,?,?,?,?)').run(c.fleetId,c.accountId,c.nodeId,randomUUID());this.humanConfig();
 }
 humanConfig(){const c=this.config(),r=this.db.prepare('SELECT * FROM fleet_realm WHERE id=1').get();need(r&&r.fleet_id===c.fleetId&&r.account_id===c.accountId&&r.node_id===c.nodeId);return c;}
 discovery(){this.humanConfig();return {...super.discovery(),enrollmentPath:'/api/fleet/enroll',enrollmentReceiptPath:'/api/fleet/enrollments/{operationId}',revocation:{consistency:'eventual',catchupIntervalMs:15000,unreachable:[...this.peerState].filter(([,s])=>s==='unavailable').map(([id])=>id)}};}
 transaction(fn){this.db.exec('BEGIN IMMEDIATE');try{const result=fn();this.db.exec('COMMIT');return result;}catch(e){this.db.exec('ROLLBACK');throw e;}}
 denied(p){return !!this.db.prepare('SELECT 1 FROM fleet_tombstones WHERE owner_device_id=? AND generation=?').get(p.deviceId,p.generation);}
 validate(p){const c=this.humanConfig();need(p&&p.sub===p.deviceId&&[p.deviceId,p.generation,p.homeNode].every(x=>uuid.test(x))&&c.members.some(m=>m.id===p.homeNode)&&!this.denied(p));return p;}
 async active(p){
  this.validate(p);
  if(p.homeNode===this.config().nodeId){const s=this.status(p.deviceId);need(s.generation===p.generation);return s;}
  return {deviceId:p.deviceId,generation:p.generation,name:typeof p.name==='string'?p.name.slice(0,100):'Fleet device'};
 }
 status(deviceId){this.humanConfig();const s=super.status(deviceId);need(!this.denied({deviceId,generation:s.generation}));return s;}
 presence(localId,p){
  const c=this.humanConfig(),old=this.db.prepare('SELECT * FROM fleet_presence WHERE local_id=? OR owner_device_id=?').get(localId,p.deviceId);
  need(!old||old.local_id===localId&&old.owner_device_id===p.deviceId&&old.home_node===p.homeNode&&old.generation===p.generation&&old.fleet_id===c.fleetId&&old.account_id===c.accountId);
  this.db.prepare('INSERT OR IGNORE INTO fleet_presence VALUES(?,?,?,?,?,?)').run(localId,p.deviceId,p.homeNode,p.generation,c.fleetId,c.accountId);
 }
 async authenticate(token){
  const c=this.humanConfig(),p=await this.verify(token,'nextcomp-device+jwt','nextcomp-fleet:'+c.fleetId,900),s=await this.active(p);
  let localId;
  if(p.homeNode===c.nodeId)localId=this.db.prepare('SELECT local_id FROM fleet_logins WHERE device_id=?').get(p.deviceId)?.local_id;
  else{
   localId=p.deviceId;const a=this.db.prepare('SELECT * FROM fleet_aliases WHERE device_id=?').get(p.deviceId);
   need(!a||a.home_node===p.homeNode&&a.local_id===localId&&a.generation===p.generation);
   need(!this.db.prepare('SELECT 1 FROM devices WHERE id=?').get(localId)||a);
   this.db.prepare('INSERT OR IGNORE INTO fleet_aliases VALUES(?,?,?,?)').run(p.deviceId,localId,p.homeNode,p.generation);
   this.db.prepare('INSERT OR IGNORE INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(localId,s.name,'fleet-alias:'+localId,this.now());
  }
  need(localId);this.presence(localId,p);return {device:{id:localId,name:s.name,authKind:'fleet-device'},principal:{...p,name:s.name}};
 }
 localPrincipal(localId){
  const c=this.humanConfig(),a=this.db.prepare('SELECT * FROM fleet_presence WHERE local_id=?').get(localId);
  if(!a)return null;need(a.fleet_id===c.fleetId&&a.account_id===c.accountId);
  const row=this.db.prepare('SELECT name FROM devices WHERE id=?').get(localId);need(row);
  return this.validate({sub:a.owner_device_id,deviceId:a.owner_device_id,generation:a.generation,homeNode:a.home_node,name:row.name});
 }
 opaqueIdentity(token){
  this.humanConfig();const row=this.db.prepare('SELECT * FROM fleet_credentials WHERE token_hash=?').get(hash(token));if(!row)return null;
  const c=this.config();need(row.fleet_id===c.fleetId&&row.account_id===c.accountId);const p=this.localPrincipal(row.local_id);
  need(p&&p.deviceId===row.owner_device_id&&p.generation===row.generation);return {device:{id:row.local_id,name:p.name,authKind:'fleet-enrolled'},principal:p};
 }
 authorizeLocal(row){
  this.humanConfig();if(!row)return null;const p=this.localPrincipal(row.id);if(p)return row;
  need(!this.db.prepare('SELECT 1 FROM fleet_aliases WHERE local_id=?').get(row.id));
  const old=this.db.prepare('SELECT device_id,generation FROM fleet_logins WHERE local_id=?').get(row.id);if(old)need(!this.denied({deviceId:old.device_id,generation:old.generation}));return row;
 }
 async authorizeAlias(localId){this.humanConfig();const a=this.db.prepare('SELECT 1 FROM fleet_aliases WHERE local_id=?').get(localId);if(a)need(this.localPrincipal(localId));else this.authorizeLocal(this.db.prepare('SELECT id FROM devices WHERE id=?').get(localId));}
 deviceActive(localId){try{return !!this.authorizeLocal(this.db.prepare('SELECT id FROM devices WHERE id=?').get(localId));}catch{return false;}}
 async context(device,principal){
  need(device?.id&&device.id!=='owner');this.humanConfig();
  const p=principal?(await this.active(principal),principal):this.login(device.id);this.validate({...p,sub:p.deviceId});this.presence(device.id,p);return {...p,sub:p.deviceId};
 }
 async session(device,principal){
  const p=await this.context(device,principal),c=this.config();
  if(principal&&p.homeNode!==c.nodeId)need(this.db.prepare('SELECT 1 FROM fleet_credentials WHERE owner_device_id=? AND generation=?').get(p.deviceId,p.generation));
  const token=await this.sign('nextcomp-device+jwt',{sub:p.deviceId,deviceId:p.deviceId,homeNode:p.homeNode,generation:p.generation,name:typeof p.name==='string'?p.name.slice(0,100):device.name},'nextcomp-fleet:'+c.fleetId,900);
  return {version:1,fleetId:c.fleetId,accountId:c.accountId,deviceId:p.deviceId,token,expiresAt:(Math.floor(this.now()/1000)+900)*1000};
 }
 credential(p){const c=this.humanConfig();return createHmac('sha256',this.credentialKey).update(JSON.stringify(['nextcomp-local-device/v1',c.fleetId,c.accountId,c.nodeId,p.deviceId,p.generation])).digest('hex');}
 enrollmentResult(row,p){
  const c=this.humanConfig();this.validate(p);need(row.owner_device_id===p.deviceId&&row.generation===p.generation);
  const saved=this.db.prepare('SELECT * FROM fleet_credentials WHERE owner_device_id=?').get(p.deviceId);need(saved&&saved.local_id===row.local_id&&saved.generation===p.generation&&saved.token_hash===hash(this.credential(p)));
  return {version:1,fleetId:c.fleetId,accountId:c.accountId,nodeId:c.nodeId,deviceId:row.local_id,ownerDeviceId:p.deviceId,token:this.credential(p)};
 }
 async enroll(device,principal,operationId){
  need(op.test(operationId));const p=await this.context(device,principal),c=this.config();
  return this.transaction(()=>{
   let row=this.db.prepare('SELECT * FROM fleet_enrollments WHERE operation_id=?').get(operationId);
   if(row)return this.enrollmentResult(row,p);
   const token=this.credential(p),old=this.db.prepare('SELECT * FROM fleet_credentials WHERE owner_device_id=?').get(p.deviceId);
   need(!old||old.local_id===device.id&&old.generation===p.generation&&old.fleet_id===c.fleetId&&old.account_id===c.accountId&&old.token_hash===hash(token));
   this.db.prepare('INSERT OR IGNORE INTO fleet_credentials VALUES(?,?,?,?,?,?)').run(hash(token),device.id,p.deviceId,p.generation,c.fleetId,c.accountId);
   this.db.prepare('INSERT INTO fleet_enrollments VALUES(?,?,?,?,?)').run(operationId,device.id,p.deviceId,p.generation,this.now());
   row=this.db.prepare('SELECT * FROM fleet_enrollments WHERE operation_id=?').get(operationId);return this.enrollmentResult(row,p);
  });
 }
 async enrollment(device,principal,operationId){need(op.test(operationId));const p=await this.context(device,principal),row=this.db.prepare('SELECT * FROM fleet_enrollments WHERE operation_id=?').get(operationId);if(!row)throw Object.assign(Error('Original enrollment is not recorded.'),{status:404});return this.enrollmentResult(row,p);}
 deny(event){
  const old=this.db.prepare('SELECT * FROM fleet_tombstones WHERE event_id=?').get(event.eventId);need(!old||old.owner_device_id===event.ownerDeviceId&&old.generation===event.generation&&old.home_node===event.homeNode&&old.created_at===event.at);
  const inserted=this.db.prepare('INSERT OR IGNORE INTO fleet_tombstones VALUES(?,?,?,?,?)').run(event.ownerDeviceId,event.generation,event.homeNode,event.eventId,event.at);
  if(inserted.changes)this.db.prepare('INSERT INTO fleet_revocations(event_id,owner_device_id,generation,home_node,created_at) VALUES(?,?,?,?,?)').run(event.eventId,event.ownerDeviceId,event.generation,event.homeNode,event.at);
 }
 revokeLocal(localId){
  this.humanConfig();const a=this.db.prepare('SELECT * FROM fleet_presence WHERE local_id=?').get(localId);let p=a?{deviceId:a.owner_device_id,generation:a.generation,homeNode:a.home_node}:null;
  if(!p){const l=this.db.prepare('SELECT * FROM fleet_logins WHERE local_id=?').get(localId);if(!l)return;p={deviceId:l.device_id,generation:l.generation,homeNode:this.config().nodeId};}
  this.transaction(()=>this.deny({eventId:randomUUID(),ownerDeviceId:p.deviceId,generation:p.generation,homeNode:p.homeNode,at:this.now()}));
 }
 async revoke(device,principal,operationId){
  need(op.test(operationId));const p=await this.context(device,principal);
  return this.transaction(()=>{
   const old=this.db.prepare('SELECT * FROM fleet_revoke_receipts WHERE operation_id=?').get(operationId);if(old){need(old.owner_device_id===p.deviceId&&old.generation===p.generation);return JSON.parse(old.result);}
   this.deny({eventId:randomUUID(),ownerDeviceId:p.deviceId,generation:p.generation,homeNode:p.homeNode,at:this.now()});
   const result={version:1,state:'revoked',operationId,ownerDeviceId:p.deviceId,propagation:'eventual'};
   this.db.prepare('INSERT INTO fleet_revoke_receipts VALUES(?,?,?,?)').run(operationId,p.deviceId,p.generation,JSON.stringify(result));return result;
  });
 }
 async revocationReceipt(token,operationId){
  need(op.test(operationId));this.humanConfig();let p;
  if(typeof token==='string'&&token.includes('.')){const c=this.config();p=await this.verify(token,'nextcomp-device+jwt','nextcomp-fleet:'+c.fleetId,900);}
  else{
   const local=this.db.prepare('SELECT local_id FROM fleet_credentials WHERE token_hash=?').get(hash(token||''))?.local_id||this.db.prepare('SELECT id FROM devices WHERE token_hash=?').get(hash(token||''))?.id;
   const a=this.db.prepare('SELECT * FROM fleet_presence WHERE local_id=?').get(local||'');need(a);p={deviceId:a.owner_device_id,generation:a.generation};
  }
  const row=this.db.prepare('SELECT * FROM fleet_revoke_receipts WHERE operation_id=?').get(operationId);need(row&&row.owner_device_id===p.deviceId&&row.generation===p.generation);return JSON.parse(row.result);
 }
 observeDeletedPairings(){
  this.humanConfig();for(const r of this.db.prepare('SELECT l.* FROM fleet_logins l LEFT JOIN devices d ON d.id=l.local_id WHERE d.id IS NULL OR d.created_at<>l.created_at').all())this.transaction(()=>this.deny({eventId:randomUUID(),ownerDeviceId:r.device_id,generation:r.generation,homeNode:this.config().nodeId,at:this.now()}));
 }
 revocationPage(after){
  this.observeDeletedPairings();const headSeq=this.db.prepare('SELECT coalesce(max(seq),0) n FROM fleet_revocations').get().n;need(Number.isSafeInteger(after)&&after>=0&&after<=headSeq);const c=this.humanConfig(),events=this.db.prepare('SELECT * FROM fleet_revocations WHERE seq>? ORDER BY seq LIMIT 256').all(after).map(r=>({seq:r.seq,eventId:r.event_id,ownerDeviceId:r.owner_device_id,generation:r.generation,homeNode:r.home_node,at:r.created_at}));
  const nextSeq=events.at(-1)?.seq??after;return {version:1,fleetId:c.fleetId,accountId:c.accountId,nodeId:c.nodeId,streamId:this.db.prepare('SELECT stream_id FROM fleet_realm WHERE id=1').get().stream_id,fromSeq:after,nextSeq,headSeq,events,hasMore:nextSeq<headSeq};
 }
 importPage(peer,page,cursor){
  const c=this.humanConfig();need(page?.version===1&&page.fleetId===c.fleetId&&page.accountId===c.accountId&&page.nodeId===peer.id&&uuid.test(page.streamId)&&(!cursor||cursor.stream_id===page.streamId)&&page.fromSeq===(cursor?.seq||0)&&Array.isArray(page.events)&&page.events.length<=256);
  let seq=page.fromSeq;for(const e of page.events){need(e.seq===++seq&&[e.eventId,e.ownerDeviceId,e.generation,e.homeNode].every(x=>uuid.test(x))&&Number.isSafeInteger(e.at)&&e.at>=0&&Object.keys(e).length===6);}
  need(page.nextSeq===seq&&Number.isSafeInteger(page.headSeq)&&page.headSeq>=seq&&typeof page.hasMore==='boolean'&&(page.hasMore?page.events.length===256&&page.headSeq>seq:page.headSeq===seq));
  this.transaction(()=>{for(const e of page.events)this.deny(e);this.db.prepare('INSERT INTO fleet_revocation_cursors VALUES(?,?,?,?) ON CONFLICT(node_id) DO UPDATE SET stream_id=excluded.stream_id,seq=excluded.seq,updated_at=excluded.updated_at').run(peer.id,page.streamId,seq,this.now());});return page.hasMore;
 }
 async syncRevocations(){
  if(this.syncing)return this.syncing;
  this.syncing=(async()=>{
   this.observeDeletedPairings();const c=this.humanConfig();
   await Promise.all(c.members.filter(m=>m.id!==c.nodeId).map(async peer=>{
    try{for(let n=0;n<4;n++){
     const cursor=this.db.prepare('SELECT * FROM fleet_revocation_cursors WHERE node_id=?').get(peer.id),path='/fleet/v1/revocations/'+(cursor?.seq||0),proof=await this.nodeProof(peer.id,{method:'GET',path,scope:'revocation:read'});
     const r=await this.fetcher(peer.origin+path,{headers:{Authorization:'Bearer '+proof},redirect:'error',signal:AbortSignal.timeout(3000)});need(r.ok);
     let text='';for await(const chunk of r.body){need(text.length+chunk.length<=262144);text+=Buffer.from(chunk).toString('utf8');}
     if(!this.importPage(peer,JSON.parse(text),cursor))break;
    }this.peerState.set(peer.id,'available');}catch{this.peerState.set(peer.id,'unavailable');}
   }));return {consistency:'eventual',peers:Object.fromEntries(this.peerState)};
  })();try{return await this.syncing;}finally{this.syncing=null;}
 }
 startRevocationSync(){const tick=()=>void this.syncRevocations().catch(()=>{});tick();const timer=setInterval(tick,15000);timer.unref();return ()=>clearInterval(timer);}
 mountStatus(app){
  super.mountStatus(app);
  app.get('/fleet/v1/revocations/:after',async(req,res)=>{try{need(/^(0|[1-9][0-9]{0,14})$/.test(req.params.after)&&!req.originalUrl.includes('?'));await this.authorizeNode((req.get('Authorization')||'').replace(/^Bearer /,''),{method:req.method,path:req.path,scope:'revocation:read'});res.set('Cache-Control','no-store').json(this.revocationPage(Number(req.params.after)));}catch{res.status(401).json({error:'Fleet revocation stream unavailable.'});}});
 }
}
