import test from 'node:test';import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';import {mkdtemp,writeFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';import {createHash} from 'node:crypto';
import {VoiceRenderings} from '../server/voice-renderings.mjs';
const hash=v=>createHash('sha256').update(v).digest('hex');
test('speech asset custody deduplicates lost acknowledgements and fences cancellation across newer listening attempts',async()=>{
 const originalId=hash('native-account-file-v1:Original passage'),cancelledId=hash('native-account-file-v1:Deferred');
 const root=await mkdtemp(join(tmpdir(),'voice-assets-')),db=new DatabaseSync(join(root,'data.sqlite'));
 const audio=Buffer.alloc(60);audio.write('RIFF');audio.write('WAVE',8);let renders=0,release;
 const store=new VoiceRenderings({db,root:join(root,'audio'),minFreeBytes:0,render:async({text,file})=>{
  renders++;if(text==='Deferred')await new Promise(r=>release=r);
  await writeFile(file,audio,{mode:0o600});return {sha256:hash(audio),durationMs:300,proof:{textSha256:hash(text),transcriptMatches:true,boundary:'turn.done+session.closed'}};
 }});
 const settle=async()=>{while(store.active||store.waiting.length)await new Promise(r=>setTimeout(r,5));};
 try{
  await store.create('phone',originalId,{text:'Original passage',attemptId:'listen-original-0001'});await settle();
  const old=await store.create('phone',originalId,{text:'Original passage',attemptId:'listen-reconnect-0002'});
  assert.equal(old.cacheHit,true);assert.equal(renders,1);assert.deepEqual((await store.audio('phone',old.id)).audio,audio);
  await assert.rejects(store.create('phone',old.id,{text:'Different passage',attemptId:'listen-altered-0001'}),/another passage/);
  assert.equal(store.get('other',old.id),null);
  await store.create('phone',cancelledId,{text:'Deferred',attemptId:'listen-cancelled-0001'});
  while(!release)await new Promise(r=>setTimeout(r,5));
  store.cancel('phone',cancelledId,'listen-cancelled-0001');
  await store.create('phone',cancelledId,{text:'Deferred',attemptId:'listen-newer-0002'});
  assert.throws(()=>store.cancel('phone',cancelledId,'listen-cancelled-0001'),/older playback/);
  const first=release;release=null;first();await settle();
  assert.equal(store.get('phone',cancelledId).phase,'completed');assert.equal(renders,2);
  await writeFile(store.path(store.row('phone',old.id)),Buffer.from('corrupt'));
  await assert.rejects(store.audio('phone',old.id),/integrity/);assert.equal(store.get('phone',old.id).phase,'corrupt');
 }finally{db.close();await rm(root,{recursive:true,force:true});}
});
