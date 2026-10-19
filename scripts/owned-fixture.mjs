import {openSync,closeSync} from 'node:fs';
import {spawn,spawnSync} from 'node:child_process';
import {setTimeout as pause} from 'node:timers/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';

// IPC ties the fixture lifetime to its caller even if the caller is SIGKILLed.
// A separate process group lets normal cleanup retire descendants as well.
export function acquireOwnedRunLock(path){
 const fd=openSync(path,'a',0o600);
 const result=spawnSync('flock',['--nonblock','3'],{stdio:['ignore','ignore','pipe',fd]});
 if(result.status!==0){closeSync(fd);throw Error('QA ownership lock unavailable; another runner may own this emulator.');}
 // flock and this process share the same open file description. Keep the fd
 // open for the whole run; the kernel releases it even on abrupt process death.
 let released=false;
 return ()=>{if(!released){released=true;closeSync(fd);}};
}
export function startOwnedFixture(script,{cwd,env,fd}){
 return spawn(process.execPath,[fileURLToPath(import.meta.url),'--child',script],{
  cwd,env,detached:true,stdio:['ignore',fd,fd,'ipc']
 });
}
export async function stopOwnedFixture(child){
 if(!child?.pid)return {stopped:true,started:false};
 const signal=sig=>{try{process.kill(-child.pid,sig);}catch(e){if(e.code!=='ESRCH')throw e;}};
 const alive=()=>{try{process.kill(-child.pid,0);return true;}catch(e){if(e.code==='ESRCH')return false;throw e;}};
 signal('SIGTERM');
 for(let n=0;n<40&&alive();n++)await pause(25);
 if(alive())signal('SIGKILL');
 for(let n=0;n<40&&alive();n++)await pause(25);
 return {pid:child.pid,stopped:!alive()};
}
if(process.argv[2]==='--child'){
 if(!process.connected)throw Error('Owned fixture requires parent IPC');
 process.once('disconnect',()=>{try{process.kill(-process.pid,'SIGKILL');}catch{process.exit(1);}});
 await import(pathToFileURL(process.argv[3]).href);
}
