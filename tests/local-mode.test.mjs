import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

test('phone-local pairing returns the separate loopback automation secret',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pocket-local-test-'));
  const port=20000+Math.floor(Math.random()*1000);
  const fixture=fileURLToPath(new URL('fixtures/app-server-stdio.mjs',import.meta.url));
  const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,POCKET_LOCAL:'1',POCKET_HOST_NAME:'This phone',POCKET_DEFAULT_CWD:dir,POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,fixture])},stdio:'ignore'});
  t.after(()=>child.kill());
  let health;
  for(let i=0;i<50;i++){try{const r=await fetch(`http://127.0.0.1:${port}/health`);if(r.ok){health=await r.json();if(health.codex)break}}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.equal(health?.codex,true);
  const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8'));
  const request=async(path,body,token=adminToken)=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,data:await r.json()}};
  const pairing=await request('/api/pairing',{});
  const device=await request('/api/pair',{code:pairing.data.code,name:'local test'},'');
  const automation=JSON.parse(await readFile(join(dir,'automation.json'),'utf8'));
  assert.equal(device.status,200);
  assert.equal(device.data.local,true);
  assert.equal(device.data.host,'This phone');
  assert.equal(device.data.automationSecret,automation.secret);
  assert.notEqual(device.data.automationSecret,device.data.token);
  assert.equal((await stat(join(dir,'automation.json'))).mode&0o777,0o600);
  const status=await fetch(`http://127.0.0.1:${port}/api/status`,{headers:{Authorization:`Bearer ${device.data.token}`}}).then(r=>r.json());
  assert.equal(status.defaultCwd,dir);
});
