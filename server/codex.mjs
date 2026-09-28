import WebSocket from 'ws';
import { EventEmitter } from 'node:events';
import { homedir } from 'node:os';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {codexConnectionError} from './connection-errors.mjs';

export class Codex extends EventEmitter {
  constructor(socket = process.env.CODEX_SOCKET || `${process.env.CODEX_HOME || homedir() + '/.codex'}/app-server-control/app-server-control.sock`,{maxPayload=100*1024*1024,command=null}={}) {
    super(); this.socket = socket; this.command=command||parseCommand(process.env.POCKET_CODEX_COMMAND);this.pending = new Map(); this.seq = 1; this.ready = false;this.maxPayload=maxPayload;this.problem=null;
  }
  executionOptions(){
    const sandbox=process.env.POCKET_CODEX_SANDBOX,approvalPolicy=process.env.POCKET_CODEX_APPROVAL_POLICY;
    return {...(sandbox?{sandbox}:{}),...(approvalPolicy?{approvalPolicy}:{})};
  }
  async connect() {
    if (this.connecting) return this.connecting;
    if (this.ready) return;
    this.connecting = new Promise((resolve, reject) => {
      let failure=null;
      const receive=data=>{
        let m; try { m = JSON.parse(data); } catch { return; }
        if (m.method) { this.emit('event', m); return; }
        const p = this.pending.get(m.id);
        if (p) { clearTimeout(p.timer); this.pending.delete(m.id); m.error ? p.reject(Object.assign(new Error(m.error.message), { rpc: m.error })) : p.resolve(m.result); }
      };
      const opened=async()=>{
        try {
          await this.call('initialize', { clientInfo: { name: 'codex_pocket', title: 'Pocket', version: '0.5.0-alpha.1' }, capabilities: { experimentalApi: true } });
          this.send({method:'initialized'}); this.ready = true; this.problem=null;this.emit('connected'); resolve();
        } catch(e) { reject(e); this.closeTransport(); }
      };
      const failed=error=>{
        failure=codexConnectionError(error.code==='WS_ERR_UNSUPPORTED_MESSAGE_LENGTH'?'CODEX_HISTORY_TOO_LARGE':this.ready?'CODEX_DISCONNECTED':'CODEX_UNAVAILABLE',error);
        if(this.transport===transport)this.problem=failure;reject(failure);
      };
      const closed=()=>{
        if(this.transport!==transport)return;
        this.ready = false; this.connecting = null;
        failure||=codexConnectionError();this.problem=failure;
        for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(failure); }
        this.pending.clear(); this.emit('disconnected',failure); reject(failure);
      };
      let transport;
      if(this.command){
        const [file,...args]=this.command;
        const child=spawn(file,args,{stdio:['pipe','pipe','pipe'],env:process.env});transport=child;this.transport=child;this.child=child;
        createInterface({input:child.stdout}).on('line',receive);
        createInterface({input:child.stderr}).on('line',line=>console.error('Codex app-server',line));
        child.once('error',failed);child.once('exit',closed);queueMicrotask(opened);
      }else{
        const ws = new WebSocket(`ws+unix://${this.socket}:/`,{maxPayload:this.maxPayload});transport=ws;this.transport=ws;this.ws=ws;
        ws.on('message',receive);ws.once('open',opened);ws.once('error',failed);ws.once('close',closed);
      }
    });
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      if (!this.canSend()) return reject(this.problem||codexConnectionError('CODEX_UNAVAILABLE'));
      const id = this.seq++;
      const timer = setTimeout(() => { this.pending.delete(id); reject(codexConnectionError('CODEX_TIMEOUT')); }, 25000);
      this.pending.set(id, {resolve, reject, timer}); this.send({id, method, params});
    });
  }
  answer(id, result) {
    if (!this.ready) throw this.problem||codexConnectionError('CODEX_UNAVAILABLE');
    this.send({id, result});
  }
  canSend(){return this.command?!!this.child?.stdin?.writable:this.ws?.readyState===WebSocket.OPEN;}
  send(value){const raw=JSON.stringify(value);if(this.command)this.child.stdin.write(raw+'\n');else this.ws.send(raw);}
  closeTransport(){if(this.command)this.child?.kill();else this.ws?.close();}
  status(){return {connected:this.ready,problem:!this.ready&&this.problem?{code:this.problem.code,message:this.problem.message}:null};}
}

function parseCommand(raw){
  if(!raw)return null;
  let value;try{value=JSON.parse(raw);}catch{throw Error('POCKET_CODEX_COMMAND must be a JSON array.');}
  if(!Array.isArray(value)||!value.length||value.some(x=>typeof x!=='string'||!x))throw Error('POCKET_CODEX_COMMAND must be a non-empty JSON string array.');
  return value;
}
