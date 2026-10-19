import test from 'node:test';import assert from 'node:assert/strict';
import {readThreadProjection} from '../server/thread-projection.mjs';
test('history projection preserves snapshot boundary and never attaches or starts runtime work',async()=>{
 const calls=[],reads=[],seeds=[];let version=12;
 const codex={async connect(){},async call(method,params){calls.push({method,params});assert.equal(method,'thread/read');return {thread:{id:'thread-one',historyMode:'paginated'}};}};
 const history={async read(metadata,options){reads.push(options);version=13;return {...metadata,turns:[],_pocketPage:{hasEarlier:true,before:'older'}};}};
 const timeline={get version(){return version;},seed(...args){seeds.push(args);}},historyHeads={snapshot:threadId=>({threadId,epoch:'one',version})};
 const result=await readThreadProjection('thread-one',{codex,history,timeline,historyHeads});
 assert.equal(result._pocketSnapshotRevision,12);assert.equal(result._pocketSnapshotHistoryHead.version,12);
 assert.deepEqual(seeds[0][1],{throughVersion:12});
 assert.deepEqual(reads[0],{before:null,preferPaging:true,maxItems:8,maxBytes:512000,maxTurns:1});
 await readThreadProjection('thread-one',{codex,history,timeline,historyHeads},{before:'older'});
 assert.equal(seeds.length,1);assert.equal(reads[1].before,'older');
 assert.deepEqual(calls.map(x=>x.method),['thread/read','thread/read']);
});
