// Required, synthetic DOM CI lane. Uses only the installed, pinned test dependency.
// Each child has a finite process-group budget; logs survive failed assertions.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {createHash} from 'node:crypto';

const root=new URL('../',import.meta.url);
const directory=resolve(process.argv[2]||'dist/ci/dom');
await mkdir(dirname(directory),{recursive:true});
await mkdir(directory); // A prior run's logs are never silently reused.
const receipt={schema:1,state:'running',startedAt:new Date().toISOString(),checks:[]};
const sha=value=>createHash('sha256').update(value).digest('hex');
const persist=()=>writeFile(join(directory,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');
await persist();

async function run(name,args,env,verify,timeoutMs=30000){
 const startedAt=Date.now();let output='',expired=false,hardKill;
 const child=spawn(process.execPath,args,{cwd:root,env:{...process.env,...env},detached:true,stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',value=>{output+=value;});
 child.stderr.on('data',value=>{output+=value;});
 const timer=setTimeout(()=>{
  expired=true;
  try{process.kill(-child.pid,'SIGTERM');}catch{}
  hardKill=setTimeout(()=>{try{process.kill(-child.pid,'SIGKILL');}catch{}},1000);
 },timeoutMs);
 let exitCode=null,error;
 try{exitCode=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve);});}
 catch(e){error=e.message;}
 finally{clearTimeout(timer);if(!expired)clearTimeout(hardKill);}
 const log=name+'.log';await writeFile(join(directory,log),output);
 const check={name,args,exitCode,expired,durationMs:Date.now()-startedAt,log,logSha256:sha(output),state:'failed'};
 receipt.checks.push(check);
 try{
  assert.equal(error,undefined,`${name}: failed to launch: ${error}`);
  assert.equal(expired,false,`${name}: exceeded ${timeoutMs} ms`);
  assert.equal(exitCode,0,`${name}: child failed; inspect ${log}`);
  await verify(output,check);
  check.state='complete';
 }catch(e){check.error=e.message;}
 await persist();
 console.log(`${name}: ${check.state}; ${join(directory,log)}`);
}

try{
 const require=createRequire(import.meta.url);
 const modulePath=require.resolve('jsdom');
 const installed=require('jsdom/package.json').version;
 const declared=JSON.parse(await readFile(new URL('package.json',root))).devDependencies?.jsdom;
 assert.equal(declared,'26.1.0','The required DOM dependency must remain exactly pinned.');
 assert.equal(installed,declared,'Installed JSDOM differs from the pinned dependency; run npm ci.');
 receipt.dependency={name:'jsdom',version:installed,module:modulePath};
 const env={NEXTCOMP_JSDOM_MODULE:modulePath,JSDOM_MODULE:modulePath};
 await run('conversation-dom',['--test','--test-reporter=tap','tests/conversation-tools-web.test.mjs'],env,(output,check)=>{
  const summary=Object.fromEntries([...output.matchAll(/^# (tests|pass|fail|cancelled|skipped|todo) (\d+)$/gm)].map(m=>[m[1],Number(m[2])]));
  check.summary=summary;
  assert.deepEqual(summary,{tests:3,pass:3,fail:0,cancelled:0,skipped:0,todo:0},'Required conversation DOM checks must all execute without skips.');
 });
 await run('execution-dom',['scripts/qualify-execution-ui.mjs'],env,(output,check)=>{
  const results=output.split(/\r?\n/).filter(line=>line.startsWith('{')).map(line=>JSON.parse(line));
  assert.equal(results.length,1,'Expected one completed execution DOM receipt.');
  const [result]=results;assert.equal(result.ok,true);assert.equal(result.journeys?.length,6);assert.equal(new Set(result.journeys).size,6);
  check.journeys=result.journeys;
 });
 await run('production-composition',['scripts/qualify-production-integration.mjs',join(directory,'production-composition')],env,async(_output,check)=>{
  const path=join(directory,'production-composition','receipt.json'),bytes=await readFile(path),result=JSON.parse(bytes);
  assert.equal(result.state,'complete','Composed qualification receipt is incomplete.');
  assert.equal(result.checks.length,3,'Both composed regression controls must execute.');
  assert.deepEqual(result.checks[0].summary,{tests:7,pass:7,fail:0,cancelled:0,skipped:0,todo:0});
  check.receipt='production-composition/receipt.json';check.receiptSha256=sha(bytes);check.sourceSha256=result.source.sha256;
 },120000);
 receipt.state=receipt.checks.every(check=>check.state==='complete')?'complete':'failed';
}catch(error){receipt.state='failed';receipt.error=error.message;await writeFile(join(directory,'setup-error.log'),error.stack+'\n');}
receipt.finishedAt=new Date().toISOString();await persist();
if(receipt.state!=='complete')process.exitCode=1;
console.log(JSON.stringify({state:receipt.state,receipt:join(directory,'receipt.json'),...(receipt.error?{error:receipt.error}:{})}));
