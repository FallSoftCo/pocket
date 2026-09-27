import WebSocket from 'ws';
import { EventEmitter } from 'node:events';
import { homedir } from 'node:os';

export class Codex extends EventEmitter {
  constructor(socket = process.env.CODEX_SOCKET || `${process.env.CODEX_HOME || homedir() + '/.codex'}/app-server-control/app-server-control.sock`) {
    super(); this.socket = socket; this.pending = new Map(); this.seq = 1; this.ready = false;
  }
  async connect() {
    if (this.connecting) return this.connecting;
    if (this.ready) return;
    this.connecting = new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws+unix://${this.socket}:/`); this.ws = ws;
      ws.on('message', data => {
        let m; try { m = JSON.parse(data); } catch { return; }
        if (m.method) { this.emit('event', m); return; }
        const p = this.pending.get(m.id);
        if (p) { clearTimeout(p.timer); this.pending.delete(m.id); m.error ? p.reject(Object.assign(new Error(m.error.message), { rpc: m.error })) : p.resolve(m.result); }
      });
      ws.on('open', async () => {
        try {
          await this.call('initialize', { clientInfo: { name: 'codex_pocket', title: 'Pocket', version: '0.4.1-alpha.1' }, capabilities: { experimentalApi: true } });
          ws.send(JSON.stringify({method:'initialized'})); this.ready = true; this.emit('connected'); resolve();
        } catch(e) { reject(e); ws.close(); }
      });
      ws.on('error', reject);
      ws.on('close', () => {
        this.ready = false; this.connecting = null;
        for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error('Codex disconnected')); }
        this.pending.clear(); this.emit('disconnected'); reject(new Error('Codex disconnected'));
      });
    });
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      if (this.ws?.readyState !== WebSocket.OPEN) return reject(new Error('Codex is offline'));
      const id = this.seq++;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${method} timed out`)); }, 25000);
      this.pending.set(id, {resolve, reject, timer}); this.ws.send(JSON.stringify({id, method, params}));
    });
  }
  answer(id, result) {
    if (!this.ready) throw new Error('Codex is offline');
    this.ws.send(JSON.stringify({id, result}));
  }
}
