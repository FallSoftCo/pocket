// Opt-in, bounded synthetic qualification. Never changes production source,
// launches Codex, contacts a provider, or installs anything on a device.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,readdir,rm,mkdtemp} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
const root=new URL('../',import.meta.url),output=process.argv[2];
if(!output||!process.env.NEXTCOMP_JSDOM_MODULE)throw Error('Usage: NEXTCOMP_JSDOM_MODULE=/existing/jsdom/lib/api.js node scripts/qualify-production-integration.mjs NEW_OUTPUT_DIRECTORY');
const directory=resolve(output);await mkdir(directory,{recursive:false});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
async function fingerprint(){
 const files=[];async function walk(path){for(const e of await readdir(new URL(path,root),{withFileTypes:true})){const p=path+'/'+e.name;if(e.isDirectory())await walk(p);else if(e.isFile())files.push(p);}}
 for(const dir of ['server','web','tests/helpers','tests/fixtures/native-contract-0167'])await walk(dir);
 files.push('package-lock.json','tests/production-continuity.integration.test.mjs','tests/production-controls.dom.mjs','scripts/qualify-production-integration.mjs');
 const hashes={};for(const f of files.sort())hashes[f]=sha(await readFile(new URL(f,root)));return {sha256:sha(JSON.stringify(hashes)),files:hashes};
}
const before=await fingerprint(),receipt={schema:1,state:'running',startedAt:new Date().toISOString(),evidence:'synthetic production HTTP/native-wire and DOM; not native runtime, rendered browser, provider or phone proof',source:before,checks:[]};
await writeFile(join(directory,'receipt.json'),JSON.stringify(receipt,null,2));
async function run(name,args,env={},expectedFailure=null){
 const started=Date.now();let text='';const child=spawn(process.execPath,args,{cwd:root,env:{...process.env,NEXTCOMP_TEST_SESSION_STORE_MODULE:'',NEXTCOMP_TEST_REQUEST_LIST_MODULE:'',...env},detached:true,stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',b=>text+=b);child.stderr.on('data',b=>text+=b);let expired=false;
 let hardKill;const timer=setTimeout(()=>{expired=true;try{process.kill(-child.pid,'SIGTERM');}catch{}hardKill=setTimeout(()=>{try{process.kill(-child.pid,'SIGKILL');}catch{}},1000);},30000);
 const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve);});clearTimeout(timer);if(!expired)clearTimeout(hardKill);
 const log=name+'.log';await writeFile(join(directory,log),text);
 const summary=Object.fromEntries([...text.matchAll(/^# (tests|pass|fail|cancelled|skipped|todo) (\d+)$/gm)].map(m=>[m[1],Number(m[2])]));
 const check={name,args,exitCode:code,durationMs:Date.now()-started,log,logSha256:sha(text),summary,expectedFailure};receipt.checks.push(check);
 assert.equal(expired,false,`${name} exceeded the 30 s bound; inspect ${log}`);
 assert.equal(summary.cancelled,0,`${name}: cancelled tests are not proof`);
 if(expectedFailure){assert.notEqual(code,0,`${name}: mutant unexpectedly passed`);assert.equal(summary.fail,1,`${name}: wrong failure shape`);assert.ok(text.includes(expectedFailure),`${name}: failed for an unrelated reason; inspect ${log}`);}
 else{assert.equal(code,0,`${name} failed; inspect ${log}`);assert.equal(summary.tests,7);assert.equal(summary.pass,7);for(const key of ['fail','skipped','todo'])assert.equal(summary[key],0,`${name}: ${key} is not acceptable`);}
}
const temp=await mkdtemp(join(tmpdir(),'nextcomp-negative-controls-'));
try{
 await run('production-composition',['--test','tests/production-continuity.integration.test.mjs','tests/production-controls.dom.mjs']);
 async function mutant(file,from,to){let source=await readFile(new URL('web/'+file,root),'utf8');assert.equal(source.split(from).length,2,'Mutation target must occur exactly once');source=source.replace(from,to).replace(/from '\.\/([^']+)'/g,(_,path)=>`from '${new URL('web/'+path,root).href}'`);const path=join(temp,file);await writeFile(path,source);return pathToFileURL(path).href;}
 const store=await mutant('session-store.mjs',"Number.isSafeInteger(ticket)&&ticket<=(entity.historyInvalidationTicket||0)||entity.retiredEpochs?.has(data.historyHead?.epoch)",'false');
 await run('reject-disabled-history-fence',['--test','--test-name-pattern=cold history replacement','tests/production-continuity.integration.test.mjs'],{NEXTCOMP_TEST_SESSION_STORE_MODULE:store},'Missing expected exception');
 const requests=await mutant('request-list.mjs','request._pocketUI?.token??null','null');
 await run('reject-request-id-only-key',['--test','--test-name-pattern=real request controls','tests/production-controls.dom.mjs'],{NEXTCOMP_TEST_REQUEST_LIST_MODULE:requests},'REUSED_REQUEST_SECRET_MUST_CLEAR');
 assert.equal((await fingerprint()).sha256,before.sha256,'Source changed during qualification');
 receipt.state='complete';receipt.finishedAt=new Date().toISOString();
}catch(error){receipt.state='failed';receipt.error=error.message;process.exitCode=1;}finally{await rm(temp,{recursive:true,force:true});await writeFile(join(directory,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');}
console.log(JSON.stringify({state:receipt.state,receipt:join(directory,'receipt.json'),checks:receipt.checks.map(({name,exitCode,summary})=>({name,exitCode,summary})),...(receipt.error?{error:receipt.error}:{})}));
