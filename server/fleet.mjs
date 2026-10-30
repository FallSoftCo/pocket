/** Fleet device authority and machine proofs are deliberately disjoint. */
import {readFileSync,openSync,closeSync,fstatSync,constants} from 'node:fs';
import {createPrivateKey,createPublicKey,randomUUID,createHash} from 'node:crypto';
import {SignJWT,jwtVerify} from 'jose';
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const invalid=()=>Object.assign(Error('Fleet authorization is unavailable or invalid.'),{status:401});
const requireValue=value=>{if(!value)throw invalid();};
export function protectedFile(path){
 const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
 try{const s=fstatSync(fd);requireValue(s.isFile()&&s.size<=65536&&!(s.mode&0o077)&&[0,process.getuid()].includes(s.uid));return readFileSync(fd,'utf8');}finally{closeSync(fd);}
}
export function fleetConfig(v){
 requireValue(v?.version===1&&[v.fleetId,v.accountId,v.nodeId].every(x=>uuid.test(x))&&typeof v.privateKeyFile==='string'&&v.privateKeyFile.startsWith('/')&&Array.isArray(v.members)&&v.members.length>0&&v.members.length<=32);
 const ids=new Set(),origins=new Set();
 for(const m of v.members){
  const u=new URL(m.origin);requireValue(uuid.test(m.id)&&typeof m.name==='string'&&m.name.length<=100&&u.protocol==='https:'&&!u.username&&!u.password&&!u.search&&!u.hash&&u.pathname==='/'&&u.origin===m.origin&&!ids.has(m.id)&&!origins.has(m.origin));
  const key=createPublicKey(m.publicKey);requireValue(key.asymmetricKeyType==='ed25519');ids.add(m.id);origins.add(m.origin);
 }
 requireValue(ids.has(v.nodeId));return v;
}
export function loadFleet(file){return fleetConfig(JSON.parse(protectedFile(file)));}
export class FleetProofs{
 constructor({config,now=Date.now}){this.getConfig=typeof config==='function'?config:()=>fleetConfig(config);this.now=now;}
 config(){return fleetConfig(this.getConfig());}
 discovery(){const c=this.config();return {version:1,id:c.fleetId,accountId:c.accountId,nodeId:c.nodeId,members:c.members.map(({id,name,origin})=>({id,name,origin})),sessionPath:'/api/fleet/session'};}
 privateKey(){const c=this.config(),key=createPrivateKey(protectedFile(c.privateKeyFile));requireValue(key.asymmetricKeyType==='ed25519'&&createPublicKey(key).export({type:'spki',format:'pem'})===createPublicKey(c.members.find(m=>m.id===c.nodeId).publicKey).export({type:'spki',format:'pem'}));return key;}
 async sign(type,claims,audience,ttl){
  const c=this.config(),iat=Math.floor(this.now()/1000);
  return new SignJWT({...claims,fleetId:c.fleetId,accountId:c.accountId}).setProtectedHeader({alg:'EdDSA',typ:type,kid:c.nodeId}).setIssuer(c.nodeId).setAudience(audience).setIssuedAt(iat).setExpirationTime(iat+ttl).setJti(randomUUID()).sign(this.privateKey());
 }
 async verify(token,type,audience,maxAge){
  try{
   requireValue(typeof token==='string'&&token.length<=8192);const c=this.config();
   const {payload:p,protectedHeader:h}=await jwtVerify(token,header=>{
    requireValue(header.alg==='EdDSA'&&header.typ===type&&Object.keys(header).every(k=>['alg','typ','kid'].includes(k)));
    const m=c.members.find(m=>m.id===header.kid);requireValue(m);return createPublicKey(m.publicKey);
   },{algorithms:['EdDSA'],typ:type,audience,issuer:c.members.map(m=>m.id),requiredClaims:['iss','aud','sub','iat','exp','jti'],currentDate:new Date(this.now()),maxTokenAge:maxAge,clockTolerance:0});
   const at=Math.floor(this.now()/1000);
   requireValue(p.iss===h.kid&&p.aud===audience&&p.fleetId===c.fleetId&&p.accountId===c.accountId&&Number.isSafeInteger(p.iat)&&Number.isSafeInteger(p.exp)&&p.iat<=at&&p.exp>at&&p.exp>p.iat&&p.exp-p.iat<=maxAge&&uuid.test(p.jti));return p;
  }catch{throw invalid();}
 }
 async nodeProof(target,{method,path,scope,sha256,deviceId}){
  const c=this.config();requireValue(c.members.some(m=>m.id===target));
  requireValue(['GET','HEAD'].includes(method)&&path.startsWith('/')&&!path.includes('?'));
  return this.sign('nextcomp-node+jwt',{sub:c.nodeId,method,path,scope,...(sha256?{sha256}:{}),...(deviceId?{deviceId}:{})},target,60);
 }
 async authorizeNode(token,expected){
  const c=this.config(),p=await this.verify(token,'nextcomp-node+jwt',c.nodeId,60);requireValue(p.sub===p.iss);
  requireValue(Object.entries(expected).every(([k,v])=>p[k]===v));return p;
 }
}
function stableDevice(c,home,id){const h=createHash('sha256').update([c.fleetId,c.accountId,home,id].join('/')).digest('hex');return h.slice(0,8)+'-'+h.slice(8,12)+'-5'+h.slice(13,16)+'-a'+h.slice(17,20)+'-'+h.slice(20,32);}
export class FleetAccess extends FleetProofs{
 constructor({db,config,now=Date.now,fetcher=fetch}){
  super({config,now});this.db=db;this.fetcher=fetcher;
  db.exec(`CREATE TABLE IF NOT EXISTS fleet_logins(local_id TEXT PRIMARY KEY,device_id TEXT UNIQUE NOT NULL,generation TEXT NOT NULL,created_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS fleet_aliases(device_id TEXT PRIMARY KEY,local_id TEXT UNIQUE NOT NULL,home_node TEXT NOT NULL,generation TEXT NOT NULL);`);
 }
 login(local){
  const c=this.config(),row=this.db.prepare('SELECT id,name,created_at FROM devices WHERE id=?').get(local);
  requireValue(row&&row.id!=='owner');let saved=this.db.prepare('SELECT * FROM fleet_logins WHERE local_id=?').get(local);
  if(saved&&saved.created_at!==row.created_at){this.db.prepare('DELETE FROM fleet_logins WHERE local_id=?').run(local);saved=null;}
  if(!saved){saved={local_id:local,device_id:stableDevice(c,c.nodeId,local),generation:randomUUID(),created_at:row.created_at};this.db.prepare('INSERT INTO fleet_logins VALUES(?,?,?,?)').run(saved.local_id,saved.device_id,saved.generation,saved.created_at);}
  return {deviceId:saved.device_id,homeNode:c.nodeId,generation:saved.generation,name:row.name};
 }
 status(deviceId){
  const row=this.db.prepare('SELECT l.*,d.name,d.created_at AS paired_at FROM fleet_logins l JOIN devices d ON d.id=l.local_id WHERE l.device_id=?').get(deviceId);
  requireValue(row&&row.created_at===row.paired_at);return {deviceId,generation:row.generation,name:row.name};
 }
 async active(p){
  const c=this.config();requireValue(uuid.test(p.deviceId)&&uuid.test(p.homeNode)&&uuid.test(p.generation)&&p.sub===p.deviceId);
  let status;
  if(p.homeNode===c.nodeId)status=this.status(p.deviceId);
  else{
   const home=c.members.find(m=>m.id===p.homeNode);requireValue(home);const path='/fleet/v1/devices/'+p.deviceId;
   const proof=await this.nodeProof(home.id,{method:'GET',path,scope:'device:status',deviceId:p.deviceId});
   const r=await this.fetcher(home.origin+path,{headers:{Authorization:'Bearer '+proof},redirect:'error',signal:AbortSignal.timeout(3000)});requireValue(r.ok);
   const body=await r.text();requireValue(body.length<=4096);status=JSON.parse(body);
  }
  requireValue(status.deviceId===p.deviceId&&status.generation===p.generation);return status;
 }
 async authenticate(token){
  const c=this.config(),p=await this.verify(token,'nextcomp-device+jwt','nextcomp-fleet:'+c.fleetId,900),status=await this.active(p);
  let localId;
  if(p.homeNode===c.nodeId)localId=this.db.prepare('SELECT local_id FROM fleet_logins WHERE device_id=?').get(p.deviceId)?.local_id;
  else{
   localId=p.deviceId; // Stable across access-token refresh, gateway and issuer changes.
   const alias=this.db.prepare('SELECT * FROM fleet_aliases WHERE device_id=?').get(p.deviceId);
   requireValue(!alias||alias.home_node===p.homeNode&&alias.local_id===localId&&alias.generation===p.generation);
   const existing=this.db.prepare('SELECT id FROM devices WHERE id=?').get(localId);requireValue(!existing||alias);
   this.db.prepare('INSERT OR IGNORE INTO fleet_aliases VALUES(?,?,?,?)').run(p.deviceId,localId,p.homeNode,p.generation);
   this.db.prepare('INSERT OR IGNORE INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(localId,String(status.name||'Fleet device').slice(0,100),'fleet-alias:'+localId,this.now());
  }
  requireValue(localId);return {device:{id:localId,name:status.name,authKind:'fleet-device'},principal:p};
 }
 async authorizeAlias(localId){
  const a=this.db.prepare('SELECT * FROM fleet_aliases WHERE local_id=?').get(localId);
  if(a)await this.active({sub:a.device_id,deviceId:a.device_id,homeNode:a.home_node,generation:a.generation});
 }
 async session(device,principal){
  requireValue(device?.id&&device.id!=='owner');const c=this.config();
  const p=principal?(await this.active(principal),principal):this.login(device.id);
  const token=await this.sign('nextcomp-device+jwt',{sub:p.deviceId,deviceId:p.deviceId,homeNode:p.homeNode,generation:p.generation},'nextcomp-fleet:'+c.fleetId,900);
  return {version:1,fleetId:c.fleetId,accountId:c.accountId,deviceId:p.deviceId,token,expiresAt:(Math.floor(this.now()/1000)+900)*1000};
 }
 mountStatus(app){
  app.get('/fleet/v1/devices/:id',async(req,res)=>{
   try{requireValue(uuid.test(req.params.id)&&!req.originalUrl.includes('?'));
    await this.authorizeNode((req.get('Authorization')||'').replace(/^Bearer /,''),{method:req.method,path:req.path,scope:'device:status',deviceId:req.params.id});
    res.set('Cache-Control','no-store').json(this.status(req.params.id));
   }catch{res.status(401).json({error:'Fleet device status unavailable.'});}
  });
 }
}
