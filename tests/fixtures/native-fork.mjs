import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
export async function nativeFixture(t){
 const base=await mkdtemp(join(tmpdir(),'pocket-terminal-fork-'));t.after(()=>rm(base,{recursive:true,force:true}));
 const source=join(base,'source'),destination=join(base,'destination'),staging=join(base,'staging');await mkdir(join(source,'sessions/2026/10/10'),{recursive:true});await mkdir(destination);
 const original={id:'source-thread',path:join(source,'sessions/2026/10/10/rollout-source-thread.jsonl')};
 const fork={id:'fork-thread',path:join(source,'sessions/2026/10/10/rollout-fork-thread.jsonl'),forkedFromId:original.id};
 const row=(ordinal,type,payload)=>JSON.stringify({ordinal,type,payload})+'\n';
 await writeFile(original.path,row(0,'session_meta',{id:original.id})+row(1,'response_item',{type:'message',role:'user',content:'Original request'})+row(2,'response_item',{type:'reasoning',encrypted_content:'OPAQUE_NATIVE_CONTEXT'})+row(3,'event_msg',{type:'task_complete',turn_id:'boundary-turn'})+row(4,'response_item',{type:'message',content:'Later unrelated source work'}));
 await writeFile(fork.path,row(4,'session_meta',{id:fork.id,forked_from_id:original.id,forked_from_ordinal_exclusive:4}));
 return {base,source,destination,staging,original,fork,codex:{async call(method,p){assert.equal(method,'thread/read');assert.equal(p.threadId,original.id);return {thread:original};}}};
}
