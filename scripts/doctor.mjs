import {existsSync,statSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {dataDir,localUrl} from './local-client.mjs';
import {Codex} from '../server/codex.mjs';
let failed=false;
const preflight=process.argv.includes('--preflight');
if(process.argv.slice(2).some(arg=>arg!=='--preflight')){console.error('Usage: npm run doctor -- [--preflight]');process.exit(2);}
const check=(ok,label)=>{console.log(`${ok?'OK':'FAIL'} ${label}`);if(!ok)failed=true;};
const [major,minor]=process.versions.node.split('.').map(Number);
check(major>22||(major===22&&minor>=13),'Node 22.13+ required (running '+process.versions.node+')');
const version=spawnSync('codex',['--version'],{encoding:'utf8',timeout:10000});
check(version.status===0,'Codex CLI: '+(version.status===0?version.stdout.trim():'not found on PATH'));
if(version.status===0&&version.stdout.trim()!=='codex-cli 0.157.1')console.log('WARN Only Codex CLI 0.157.1 has been verified with this release.');
const socket=process.env.CODEX_SOCKET||join(process.env.CODEX_HOME||join(homedir(),'.codex'),'app-server-control/app-server-control.sock');
let hasSocket=false;try{hasSocket=statSync(socket).isSocket();}catch{}
check(hasSocket,'Existing shared Codex socket');
if(!hasSocket)console.log('Start an interactive Codex CLI 0.157.1 session, then retry. Use the same OS user and CODEX_HOME. See docs/DEPLOYMENT.md; do not start a competing app-server for an existing task.');
if(hasSocket){
  const c=new Codex(socket);
  try{await c.connect();const r=await c.call('thread/list',{limit:1,archived:false});check(Array.isArray(r.data),'Shared app-server initialize and thread/list');}
  catch(e){check(false,'Shared app-server: '+e.message);}
  finally{c.ws?.close();}
}
if(!preflight){
 check(existsSync(join(dataDir,'firebase-client.json')),'Firebase client configured');
 try{const r=await fetch(new URL('/health',localUrl),{signal:AbortSignal.timeout(3000)});const s=await r.json();check(r.ok&&s.ok,'Pocodex backend');check(s.codex===true,'Pocodex backend connected to Codex');check(s.push==='fcm','Firebase sender configured');}
 catch{check(false,'Pocodex backend: start npm start first');}
}else if(!failed)console.log('Preflight passed. Continue with Firebase and private HTTPS setup.');
process.exitCode=failed?1:0;
