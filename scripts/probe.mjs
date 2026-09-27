import { Codex } from '../server/codex.mjs';
const c = new Codex();
await c.connect();
const loaded=await c.call('thread/loaded/list');
console.log('Connected to stock Codex. Loaded thread count:',loaded.data?.length ?? loaded);
const id=process.env.CODEX_THREAD_ID;
if(id){const r=await c.call('thread/read',{threadId:id,includeTurns:false}); console.log(JSON.stringify({id:r.thread.id,name:r.thread.name,status:r.thread.status,cwd:r.thread.cwd}));}
c.ws.close();
