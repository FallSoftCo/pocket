import WebSocket from 'ws';
import { EventEmitter } from 'node:events';
import { homedir } from 'node:os';
import {codexConnectionError} from './connection-errors.mjs';

export class Codex extends EventEmitter {
  constructor(socket = process.env.CODEX_SOCKET || `${process.env.CODEX_HOME || homedir() + '/.codex'}/app-server-control/app-server-control.sock`,{maxPayload=100*1024*1024}={}) {
    super(); this.socket = socket; this.pending = new Map(); this.seq = 1; this.ready = false;this.maxPayload=maxPayload;this.problem=null;
  }
  async connect() {
    if (this.connecting) return this.connecting;
    if (this.ready) return;
    this.connecting = new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws+unix://${this.socket}:/`,{maxPayload:this.maxPayload}); this.ws = ws;
      let failure=null;
      ws.on('message', data => {
        let m; try { m = JSON.parse(data); } catch { return; }
        if (m.method) { this.emit('event', m); return; }
        const p = this.pending.get(m.id);
        if (p) { clearTimeout(p.timer); this.pending.delete(m.id); m.error ? p.reject(Object.assign(new Error(m.error.message), { rpc: m.error })) : p.resolve(m.result); }
      });
      ws.on('open', async () => {
        try {
          await this.call('initialize', { clientInfo: { name: 'codex_pocket', title: 'Pocket', version: '0.4.4-alpha.2' }, capabilities: { experimentalApi: true } });
          ws.send(JSON.stringify({method:'initialized'})); this.ready = true; this.problem=null;this.emit('connected'); resolve();
        } catch(e) { reject(e); ws.close(); }
      });
      ws.on('error', error=>{
        failure=codexConnectionError(error.code==='WS_ERR_UNSUPPORTED_MESSAGE_LENGTH'?'CODEX_HISTORY_TOO_LARGE':this.ready?'CODEX_DISCONNECTED':'CODEX_UNAVAILABLE',error);
        if(this.ws===ws)this.problem=failure;reject(failure);
      });
      ws.on('close', () => {
        if(this.ws!==ws)return;
        this.ready = false; this.connecting = null;
        failure||=codexConnectionError();this.problem=failure;
        for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(failure); }
        this.pending.clear(); this.emit('disconnected',failure); reject(failure);
      });
    });
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      if (this.ws?.readyState !== WebSocket.OPEN) return reject(this.problem||codexConnectionError('CODEX_UNAVAILABLE'));
      const id = this.seq++;
      const timer = setTimeout(() => { this.pending.delete(id); reject(codexConnectionError('CODEX_TIMEOUT')); }, 25000);
      this.pending.set(id, {resolve, reject, timer}); this.ws.send(JSON.stringify({id, method, params}));
    });
  }
  answer(id, result) {
    if (!this.ready) throw this.problem||codexConnectionError('CODEX_UNAVAILABLE');
    this.ws.send(JSON.stringify({id, result}));
  }
  status(){return {connected:this.ready,problem:!this.ready&&this.problem?{code:this.problem.code,message:this.problem.message}:null};}
}
