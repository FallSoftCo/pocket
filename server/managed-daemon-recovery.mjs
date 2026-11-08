import {spawn} from 'node:child_process';
import {isAbsolute} from 'node:path';

// Recovery is explicitly enrolled for a locally managed Codex installation.
// It uses Codex's locked, idempotent start operation, never restart/stop or a
// second app-server process. There are no thread/turn calls or command replays.
export class ManagedDaemonRecovery {
  constructor(file,{clock=Date.now,run=start,cooldownMs=120000}={}){
    if(file&&!isAbsolute(file))throw Error('Managed Codex executable must be absolute.');
    Object.assign(this,{file,clock,run,cooldownMs,lastAttempt:-Infinity,pending:null});
  }
  recover(error){
    if(!this.file||!['ECONNREFUSED','ENOENT'].includes(error?.cause?.code))return Promise.resolve(false);
    if(this.pending)return this.pending;
    if(this.clock()-this.lastAttempt<this.cooldownMs)return Promise.resolve(false);
    this.lastAttempt=this.clock();
    const operation=Promise.resolve().then(()=>this.run(this.file)).then(()=>true,()=>false);
    this.pending=operation;
    operation.finally(()=>{if(this.pending===operation)this.pending=null;});
    return operation;
  }
}
function start(file){
  return new Promise((resolve,reject)=>{
    const child=spawn(file,['app-server','daemon','start'],{stdio:'ignore',env:process.env});
    const timer=setTimeout(()=>child.kill('SIGTERM'),15000);
    child.once('error',error=>{clearTimeout(timer);reject(error);});
    child.once('exit',(code,signal)=>{clearTimeout(timer);if(code===0&&!signal)resolve();else reject(Error('Managed Codex start unavailable.'));});
  });
}
