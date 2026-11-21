// Actual pinned runtime lifecycle proof. Isolated empty home; no account, model
// turn, customer source, tool execution or production transport is used.
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';import {join}from'node:path';
import http from 'node:http';import {once}from'node:events';
import {Codex}from'../server/codex.mjs';import {ImmersionRuntime}from'../server/immersion-runtime.mjs';
const binary=process.env.NEXTCOMP_NATIVE_TEST_BINARY;if(!binary||!existsSync(binary))throw Error('Explicit native test binary required.');
const root=mkdtempSync(join(tmpdir(),'nextcomp-immersion-native-')),home=join(root,'profile');mkdirSync(home);
let mcpRequests=0;const fixture=http.createServer((_q,r)=>{mcpRequests++;r.writeHead(500).end();});fixture.listen(0,'127.0.0.1');await once(fixture,'listening');
writeFileSync(join(home,'config.toml'),`[mcp_servers.unneeded_fixture]\nurl="http://127.0.0.1:${fixture.address().port}/mcp"\n`);
process.env.CODEX_HOME=home;process.env.HOME=root;delete process.env.OPENAI_API_KEY;
const codex=new Codex(undefined,{command:[binary,'app-server','--listen','stdio://'],processCwd:root,requestTimeoutMs:3000});
const runtime=new ImmersionRuntime(codex,{ownsTransport:true});const receipts=[];
const deadline=setTimeout(()=>codex.closeTransport(),45000);
try{
 for(let i=0;i<4;i++){
  const id=await runtime.ensure({cwd:root,ephemeral:false,approvalPolicy:'never',sandbox:'read-only'});
  const during=await codex.call('thread/loaded/list',{});assert.deepEqual(during.data,[id]);
  await runtime.release();
  const after=await codex.call('thread/loaded/list',{});assert.deepEqual(after.data,[]);
  receipts.push({cycle:i,oneThreadWhileOwned:true,zeroLoadedAfterRelease:true});
 }
 assert.equal(mcpRequests,0);
 console.log(JSON.stringify({state:'passed',receipts,mcpRequests,modelTurns:0,productionProfile:false}));
}finally{
 clearTimeout(deadline);try{await runtime.close();}finally{codex.closeTransport();fixture.close();}
 if(codex.child&&codex.child.exitCode===null)await once(codex.child,'exit');
 fixture.close();
}
