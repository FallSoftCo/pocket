import {readFileSync,mkdirSync} from 'node:fs';
import {join,dirname} from 'node:path';
import {homedir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {EventQueue,EventPump,webhookServer} from './events.mjs';
import {GitHub} from './github.mjs';
import {githubCredential} from './auth.mjs';
import {reconsider,completedRun,postMerge} from './jobs.mjs';
import {failureNotifier} from './alerts.mjs';

const configPath=process.env.MAINTAINER_CONFIG||join(homedir(),'.config/pocket-maintainer/config.json');
const config=JSON.parse(readFileSync(configPath,'utf8'));
if(config.enabled!==true)throw Error('Maintainer disabled');
mkdirSync(config.dataDir,{recursive:true,mode:0o700});
const secret=readFileSync(config.webhook.secretFile,'utf8').trim();
const queue=new EventQueue(join(config.dataDir,'events.sqlite'));
const credential=githubCredential(config);
const github=()=>new GitHub(credential);
const alert=failureNotifier(config,queue);
const worker=join(dirname(fileURLToPath(import.meta.url)),'worker.mjs');
async function processJob(job){
 if(job.key.startsWith('comment:'))return reconsider(github(),queue,Number(job.key.slice(8)));
 if(job.key.startsWith('run:'))return completedRun(github(),queue,Number(job.key.slice(4)),alert);
 if(job.key.startsWith('postmerge:'))return postMerge(github(),queue,Number(job.key.slice(10)),alert);
 if(job.key==='reconcile'||job.key.startsWith('commit:')){
  const gh=github();
  const prs=await gh.pages(job.key==='reconcile'?`${gh.root}/pulls?state=open`:`${gh.root}/commits/${job.key.slice(7)}/pulls`);
  for(const p of prs)if(p.state==='open'&&p.base.ref==='main')queue.enqueue(`pr:${p.number}`);
  return;
 }
 const number=job.key.slice(3);
 const child=spawn('/usr/bin/flock',['-n',join(config.dataDir,'worker.lock'),process.execPath,worker,'--pr',number],{env:{...process.env,MAINTAINER_CONFIG:configPath},stdio:['ignore','pipe','pipe'],detached:true});
 const stop=()=>{try{process.kill(-child.pid,'SIGTERM');}catch{}};
 let output='';
 child.stdout.on('data',b=>{output+=b;if(output.length>1000000)stop();});
 child.stderr.on('data',b=>process.stderr.write(b));
 const timeout=setTimeout(stop,15*60000);
 const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',resolve);}).finally(()=>clearTimeout(timeout));
 if(code!==0)throw Error('PR processing failed');
 let result;
 for(const line of output.trim().split('\n')){
  let record;try{record=JSON.parse(line);}catch{throw Error('Invalid worker response');}
  if(record.workerResult)result=record.workerResult;else console.log(line);
 }
 if(!result)throw Error('Missing worker result');
 return result;
}
const pump=new EventPump(queue,processJob,{onFailure:async(job,retryAt)=>{
 if(job.attempts>=1||retryAt===null)await alert(`worker:${job.key}`,'Pocodex maintainer needs attention',`Automatic processing of ${job.key} has failed repeatedly. ${retryAt===null?'Retries are exhausted.':'Recovery will retry automatically.'} Check the maintainer service log.`);
}});
const server=webhookServer({secret,repositoryId:config.webhook.repositoryId,queue,wake:()=>pump.wake()});
server.listen(config.webhook.port||18881,'127.0.0.1',()=>{
 console.log('Pocodex Maintainer subscribed webhook listener ready');
 // One catch-up on service start recovers current state after a host outage.
 queue.enqueue('reconcile');pump.wake();
});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{pump.stop();server.close();setTimeout(()=>process.exit(0),100).unref();});
