import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync,openSync,closeSync,readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {generateKeyPairSync,randomUUID,randomBytes} from 'node:crypto';
import {createServer} from 'node:net';
import {fileURLToPath} from 'node:url';
import {openStore,hash} from '../server/store.mjs';
import {startOwnedFixture,stopOwnedFixture} from '../scripts/owned-fixture.mjs';
test('actual gateway mounted discovery/session: paired login survives restart, owner and node cannot mint human sessions',async()=>{
 const root=mkdtempSync(resolve(tmpdir(),'nextcomp-gateway-')),data=resolve(root,'data'),key=generateKeyPairSync('ed25519'),nodeId=randomUUID(),device=randomUUID(),opaque=randomBytes(32).toString('hex');
 const config={version:1,fleetId:randomUUID(),accountId:randomUUID(),nodeId,privateKeyFile:resolve(root,'private.pem'),members:[{id:nodeId,name:'Synthetic node',origin:'https://fixture.invalid',publicKey:key.publicKey.export({type:'spki',format:'pem'})}]};
 writeFileSync(config.privateKeyFile,key.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});const configFile=resolve(root,'fleet.json');writeFileSync(configFile,JSON.stringify(config),{mode:0o600});
 const {db,secrets}=openStore(data);db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(device,'Paired fixture only',hash(opaque),Date.now());db.close();
 let port;await new Promise(r=>{const s=createServer();s.listen(0,'127.0.0.1',()=>{port=s.address().port;s.close(r);});});
 const log=resolve(root,'gateway.log');let child;
 async function launch(){const fd=openSync(log,'a',0o600);child=startOwnedFixture(fileURLToPath(new URL('../server/index.mjs',import.meta.url)),{cwd:root,env:{...process.env,PORT:String(port),POCKET_DATA:data,POCKET_FLEET_CONFIG:configFile,POCKET_EXECUTION_BACKEND:'hollywood',POCKET_LOCAL:'',POCKET_VOICE_API_KEY:'',POCKET_BACKEND_ARTIFACTS_DIR:'',POCKET_BACKEND_ARTIFACTS_KEY_FILE:''},fd});closeSync(fd);
  for(let i=0;i<80;i++){if(child.exitCode!==null)throw Error(readFileSync(log,'utf8'));try{if((await fetch('http://127.0.0.1:'+port+'/health')).ok)return;}catch{}await new Promise(r=>setTimeout(r,50));}throw Error('Owned fixture readiness timeout');
 }
 const call=(path,token,method='GET')=>fetch('http://127.0.0.1:'+port+path,{method,headers:{Authorization:'Bearer '+token}});
 try{
  await launch();assert.equal((await call('/api/fleet/session',secrets.adminToken,'POST')).status,401);
  const discovery=await (await call('/api/backends',opaque)).json();assert.equal(discovery.fleet.id,config.fleetId);assert.equal(discovery.fleet.members.length,1);
  const response=await call('/api/fleet/session',opaque,'POST');assert.equal(response.status,200);const session=await response.json();
  assert.equal((await call('/api/backends',session.token)).status,200);
  assert.equal((await call('/api/hollywood/v3/rooms/fake/messages',secrets.adminToken,'POST')).status,403);
  await stopOwnedFixture(child);await launch();
  const refresh=await (await call('/api/fleet/session',session.token,'POST')).json();assert.equal(refresh.deviceId,session.deviceId);assert.notEqual(refresh.token,session.token);
  // Original paired device row is unchanged; no token/identity replacement.
  const check=openStore(data);assert.equal(check.db.prepare('SELECT token_hash FROM devices WHERE id=?').get(device).token_hash,hash(opaque));check.db.prepare('DELETE FROM devices WHERE id=?').run(device);check.db.close();
  assert.equal((await call('/api/backends',refresh.token)).status,401);
 }finally{await stopOwnedFixture(child);rmSync(root,{recursive:true,force:true});}
});
