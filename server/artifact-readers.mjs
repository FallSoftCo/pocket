/** Artifact-only node keys are NOT gateway/human-session issuer authority. */
import {createPublicKey} from 'node:crypto';
import {jwtVerify} from 'jose';
const uuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const denied=()=>Object.assign(Error('Artifact reader authority is invalid.'),{status:401});
const check=v=>{if(!v)throw denied();};
export const ARTIFACT_READER_TYPE='nextcomp-artifact-reader+jwt';
export function artifactReaders(config){
 const readers=config.artifactReaders??[];check(Array.isArray(readers)&&readers.length<=128);
 const ids=new Set(config.members.map(m=>m.id));
 const keys=new Set(config.members.map(m=>createPublicKey(m.publicKey).export({type:'spki',format:'der'}).toString('hex')));
 for(const r of readers){
  check(Object.keys(r).sort().join(',')==='id,publicKey'&&uuid.test(r.id)&&!ids.has(r.id));
  const key=createPublicKey(r.publicKey),raw=key.export({type:'spki',format:'der'}).toString('hex');
  check(key.asymmetricKeyType==='ed25519'&&!keys.has(raw));ids.add(r.id);keys.add(raw);
 }
 return readers;
}
export async function authorizeArtifactReader(token,config,expected,now){
 try{
  check(expected.scope==='artifact:read'&&['GET','HEAD'].includes(expected.method)&&
    /^[a-f0-9]{64}$/.test(expected.sha256)&&expected.path==='/backend-artifacts/v1/blobs/'+expected.sha256);
  const readers=artifactReaders(config);check(readers.length>0&&typeof token==='string'&&token.length<=8192);
  const {payload:p,protectedHeader:h}=await jwtVerify(token,header=>{
   check(header.alg==='EdDSA'&&header.typ===ARTIFACT_READER_TYPE&&Object.keys(header).every(k=>['alg','typ','kid'].includes(k)));
   const reader=readers.find(r=>r.id===header.kid);check(reader);return createPublicKey(reader.publicKey);
  },{algorithms:['EdDSA'],typ:ARTIFACT_READER_TYPE,audience:config.nodeId,issuer:readers.map(r=>r.id),
    requiredClaims:['iss','sub','aud','iat','exp','jti'],currentDate:new Date(now),maxTokenAge:60,clockTolerance:0});
  check(Object.keys(p).sort().join(',')==='accountId,aud,exp,fleetId,iat,iss,jti,method,path,scope,sha256,sub');
  const at=Math.floor(now/1000);
  check(p.iss===h.kid&&p.sub===p.iss&&p.aud===config.nodeId&&p.fleetId===config.fleetId&&p.accountId===config.accountId&&
    Number.isSafeInteger(p.iat)&&Number.isSafeInteger(p.exp)&&p.iat<=at&&p.exp>at&&p.exp>p.iat&&p.exp-p.iat<=60&&uuid.test(p.jti));
  check(Object.entries(expected).every(([k,v])=>p[k]===v));return p;
 }catch{throw denied();}
}
