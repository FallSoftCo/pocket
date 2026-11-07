import test from 'node:test';
import assert from 'node:assert/strict';
import {inactiveArchiveReason} from '../server/session-archive.mjs';
import {archiveInactive} from '../scripts/archive-inactive-sessions.mjs';
const cutoff=1794000000000-30*86400000;
const old={id:'old',status:{type:'notLoaded'},createdAt:cutoff/1000-100,recencyAt:cutoff/1000-10};
test('inactive cleanup uses work recency and requires safe known state',()=>{
 assert.equal(inactiveArchiveReason(old,cutoff),null);
 assert.equal(inactiveArchiveReason({...old,updatedAt:cutoff/1000+10000},cutoff),null,'metadata maintenance must not renew old work');
 assert.ok(inactiveArchiveReason({...old,recencyAt:null,updatedAt:cutoff/1000+1},cutoff),'older runtimes retain the metadata fallback');
 for(const t of [{...old,status:{type:'active'}},{...old,activityAt:cutoff+1},{...old,status:{type:'unknown'}},{...old,needsInput:true}])assert.ok(inactiveArchiveReason(t,cutoff));
});
test('full paginated inventory precedes writes; protected descendants keep their parents; unknown reply stops without resending',async()=>{
 const calls=[],saved=[];
 const request=async(path,options)=>{calls.push([path,options]);if(!options&&path.includes('archived=true'))return {threads:[]};if(!options)return path.includes('cursor')?{threads:[{...old,id:'child',parentThreadId:'parent',status:{type:'active'}}]}:{threads:[old,{...old,id:'parent'},{...old,id:'next'}],nextCursor:'page2'};throw Error('reply lost');};
 const r=await archiveInactive({request,cutoff,apply:true,save:r=>saved.push(structuredClone(r))});
 assert.equal(calls.length,4);assert.match(calls[0][0],/includeChildren=true/);assert.match(calls[2][0],/cursor=page2/);assert.equal(r.state,'needs-reconciliation');assert.equal(r.results[0].state,'unknown');assert.equal(calls[3][1].body.inactiveBefore,cutoff);
 const noWrite=await archiveInactive({request:async(path)=>path.includes('archived=true')?{threads:[]}:path.includes('cursor')?{threads:[{...old,id:'child',parentThreadId:'parent',status:{type:'active'}}]}:{threads:[old,{...old,id:'parent'}],nextCursor:'page2'},cutoff,save:()=>{}});
 assert.equal(noWrite.results.find(r=>r.id==='parent').state,'skipped');assert.equal(noWrite.results.find(r=>r.id==='old').state,'eligible');
});
