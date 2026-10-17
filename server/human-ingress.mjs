import {randomBytes,createHash} from 'node:crypto';
const fail=message=>Object.assign(Error(message),{status:403,code:'HUMAN_DEVICE_REQUIRED'});
const digest=value=>createHash('sha256').update(JSON.stringify(value??{})).digest('hex');
export function humanInputRoute(method,path){
 if(method==='POST'&&/^\/api\/voice\/renderings\/[a-zA-Z0-9_-]{8,100}(?:\/cancel)?$/.test(path))return true;
 if(method==='PUT'&&/^\/api\/voice\/recordings\/[^/]+\/audio$/.test(path))return true;
 if(method==='POST'&&/^\/api\/voice\/recordings(?:\/[^/]+\/cancel)?$/.test(path))return true;
 return method==='POST'&&(path==='/api/threads'||/^\/api\/threads\/[^/]+\/(?:reply|queue\/resume|replies\/[^/]+)$/.test(path)||/^\/api\/voice\/(?:start|text|native\/(?:start|input|commit)|turns\/[^/]+)$/.test(path));
}
export class HumanIngress{
 constructor(db,{now=Date.now}={}){
  this.db=db;this.now=now;this.delegations=new Map();
  db.exec('CREATE TABLE IF NOT EXISTS human_admissions(kind TEXT NOT NULL,request_id TEXT NOT NULL,principal TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,request_id)); CREATE TABLE IF NOT EXISTS human_admission_actions(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,request_id TEXT NOT NULL,action TEXT NOT NULL,principal TEXT NOT NULL,created_at INTEGER NOT NULL);');
 }
 active(actor){return actor&&typeof actor.deviceId==='string'&&typeof actor.voiceTurnId==='string'&&this.db.prepare('SELECT 1 FROM devices WHERE id=?').get(actor.deviceId)&&this.db.prepare("SELECT 1 FROM voice_turns WHERE device=? AND id=? AND state='thinking'").get(actor.deviceId,actor.voiceTurnId);}
 delegate(actor,method,path,body){
  if(!humanInputRoute(method,path))return {headers:{},release(){}};
  if(!this.active(actor))throw fail('Voice control needs its original paired device and active voice turn.');
  const at=this.now();for(const [key,value] of this.delegations)if(value.expires<=at)this.delegations.delete(key);
  const key=randomBytes(32).toString('base64url');
  this.delegations.set(key,{method,path,body:digest(body),expires:at+35000,principal:{version:1,kind:'voice',deviceId:actor.deviceId,voiceTurnId:actor.voiceTurnId}});
  return {headers:{'X-NextComp-Voice-Delegation':key},release:()=>this.delegations.delete(key)};
 }
 middleware(req,res,next){
  if(!humanInputRoute(req.method,req.originalUrl.split('?')[0]))return next();
  try{
   if(req.device?.id&&req.device.id!=='owner'){req.humanPrincipal={version:1,kind:'paired-device',deviceId:req.device.id};return next();}
   const key=req.get('X-NextComp-Voice-Delegation'),grant=this.delegations.get(key);if(key)this.delegations.delete(key);
   if(!grant||grant.expires<=this.now()||grant.method!==req.method||grant.path!==req.originalUrl.split('?')[0]||grant.body!==digest(req.body))throw fail('Human input requires a paired device. Agents must use native interagent messaging.');
   if(!this.active(grant.principal))throw fail('The originating voice request is no longer active.');
   req.device=this.db.prepare('SELECT id,name FROM devices WHERE id=?').get(grant.principal.deviceId);req.humanPrincipal=grant.principal;next();
  }catch(error){res.status(error.status||403).json({error:error.message,code:error.code||'HUMAN_DEVICE_REQUIRED'});}
 }
 record(kind,id,principal){
  if(!principal||!['paired-device','voice'].includes(principal.kind)||!principal.deviceId)throw fail('Missing authenticated human provenance.');
  if(this.db.prepare('SELECT principal FROM human_admissions WHERE kind=? AND request_id=?').get(kind,id))return;
  this.db.prepare('INSERT INTO human_admissions VALUES(?,?,?,?)').run(kind,id,JSON.stringify(principal),this.now());
 }
 action(kind,id,action,principal){
  if(!principal?.deviceId)throw fail('Missing authenticated retry provenance.');
  this.db.prepare('INSERT INTO human_admission_actions(kind,request_id,action,principal,created_at) VALUES(?,?,?,?,?)').run(kind,id,action,JSON.stringify(principal),this.now());
 }
 inherit(fromKind,fromId,kind,id){
  const original=this.db.prepare('SELECT principal FROM human_admissions WHERE kind=? AND request_id=?').get(fromKind,fromId);if(original)this.record(kind,id,JSON.parse(original.principal));
 }
}

export function admissionTransaction(db,work){db.exec('BEGIN IMMEDIATE');try{const result=work();db.exec('COMMIT');return result;}catch(error){db.exec('ROLLBACK');throw error;}}
