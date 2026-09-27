import {existsSync,statSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {dataDir,localUrl} from './local-client.mjs';
import {Codex} from '../server/codex.mjs';
let failed=false;
const check=(ok,label)=>{console.log(`${ok?'OK':'FAIL'} ${label}`);if(!ok)failed=true;};
const [major,minor]=process.versions.node.split('.').map(Number);
check(major>22||(major===22&&minor>=13),'Node 22.13+ required (running '+process.versions.node+')');
const version=spawnSync('codex',['--version'],{encoding:'utf8'});
check(version.status===0,'Codex CLI: '+(version.status===0?version.stdout.trim():'not found on PATH'));
const socket=process.env.CODEX_SOCKET||join(process.env.CODEX_HOME||join(homedir(),'.codex'),'app-server-control/app-server-control.sock');
check(existsSync(socket)&&statSync(socket).isSocket(),'Existing shared Codex socket');
if(existsSync(socket)){
  const c=new Codex(socket);
  try{await c.connect();const r=await c.call('thread/list',{limit:1,archived:false});check(Array.isArray(r.data),'Shared app-server initialize and thread/list');}
  catch(e){check(false,'Shared app-server: '+e.message);}
  finally{c.ws?.close();}
}
check(existsSync(join(dataDir,'firebase-client.json')),'Firebase client configured');
try{const r=await fetch(new URL('/health',localUrl),{signal:AbortSignal.timeout(3000)});const s=await r.json();check(r.ok&&s.ok,'Pocket backend');check(s.push==='fcm','Firebase sender configured');}
catch{check(false,'Pocket backend: start npm start first');}
process.exitCode=failed?1:0;
