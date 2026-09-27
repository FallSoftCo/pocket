import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,renameSync,existsSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {homedir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {GitHub} from './github.mjs';
import {review} from './reviewer.mjs';
import {decide,eligible,secretLike,proseOnly} from './core.mjs';
const ownerReview=summary=>({verdict:'owner',summary,findings:[],policyRule:'',evidence:[],risk:'high'});
export async function tick({gh,config,state,save,reviewer=review,notify=async()=>{}}){
 const now=Date.now(),day=new Date(now).toISOString().slice(0,10);
 if(state.day!==day){state.day=day;state.reviews=0;}
 const prs=await gh.request(`${gh.root}/pulls?state=open&sort=updated&direction=desc&per_page=30`);
 for(const p of prs.slice(0,10)){
  if(p.draft||p.base.ref!=='main'||p.labels.some(l=>l.name==='maintainer:hold'))continue;
  const s=await gh.snapshot(p.number);if(s.state!=='open'||s.draft||s.labels.includes('maintainer:hold'))continue;
  let item=state.items[String(s.number)];
  if(!item||item.key!==s.key){
   if(state.reviews>=(config.maxReviewsPerDay||20))break;
   item={key:s.key,head:s.head,base:s.base};state.items[String(s.number)]=item;
   let r,second=null;
   if(!eligible(s))r=ownerReview('This change exceeds the automatic review context limits; a maintainer should review it.');
   else if(secretLike(s))r=ownerReview('Potential sensitive material needs private review; no details will be quoted publicly.');
   else if(s.files.some(f=>f.filename==='MAINTAINER_POLICY.md'||f.filename.startsWith('maintainer/')||f.filename.startsWith('.github/')))r=ownerReview('Changes to maintainer authority or automation require the owner.');
   else{
    state.reviews++;save();r=await reviewer(s,config.reviewer||{});
    if(r.verdict==='decline'||(r.verdict==='approve'&&!(r.risk==='low'&&proseOnly(s)))){
     if(state.reviews<(config.maxReviewsPerDay||20)){state.reviews++;save();second=await reviewer(s,config.reviewer||{});}
    }
   }
   item.result={review:r,second};item.decision=decide(s,r,second);save();
  }
  // Interrupted reviews are retried, never treated as approvals.
  if(!item.result){delete state.items[String(s.number)];save();continue;}
  const action=item.decision.action;
  if(config.dryRun){console.log(JSON.stringify({pr:s.number,action,head:s.head}));continue;}
  if(config.notifyReviews===true&&!item.notified&&['owner','approve'].includes(action)){
   await notify({number:s.number,url:s.url,head:s.head,title:`Pocket PR #${s.number} needs your review`,message:`${s.title}\n\n${item.decision.reason}\n\n${s.url}\nReviewed commit: ${s.head.slice(0,12)}. Open GitHub to review or merge; maintainer:hold pauses automation.`});
   item.notified=true;save();
  }
  if(item.dispatched){
   const comments=await gh.comments(s.number);const receipt=comments.some(c=>c.user.login==='github-actions[bot]'&&c.body.includes(`<!-- pocket-maintainer:${s.key} -->`));
   if(receipt&&action!=='merge')continue;
   if(receipt&&action==='merge'&&!await gh.checksPass(s))continue;
   if(now-item.dispatched<(receipt?2:10)*60000)continue;
  }
  await gh.request(`${gh.root}/actions/workflows/maintainer.yml/dispatches`,{method:'POST',body:{ref:'main',inputs:{pr:String(s.number),head:s.head,base:s.base,key:s.key,result:JSON.stringify(item.result)}}});
  item.dispatched=now;save();console.log(JSON.stringify({pr:s.number,action,state:'dispatched',head:s.head.slice(0,12)}));
 }
}
async function main(){
 const configPath=process.env.MAINTAINER_CONFIG||join(homedir(),'.config/pocket-maintainer/config.json');
 const config=JSON.parse(readFileSync(configPath,'utf8'));
 if(config.enabled!==true){console.log('Maintainer disabled');return;}
 const data=resolve(config.dataDir);mkdirSync(data,{recursive:true,mode:0o700});
 const stateFile=join(data,'state.json');const state=existsSync(stateFile)?JSON.parse(readFileSync(stateFile,'utf8')):{items:{}};
 const save=()=>{writeFileSync(stateFile+'.tmp',JSON.stringify(state),{mode:0o600});renameSync(stateFile+'.tmp',stateFile);};
 const token=process.env.GH_TOKEN||execFileSync('gh',['auth','token'],{encoding:'utf8'}).trim();
 const gh=new GitHub(token);
 const notify=async n=>{
  if(!config.pocket)return;
  const url=new URL(config.pocket.url||'http://127.0.0.1:18880');
  if(url.hostname!=='127.0.0.1'||url.protocol!=='http:')throw Error('Pocket notification endpoint must be loopback');
  const {adminToken}=JSON.parse(readFileSync(join(config.pocket.dataDir,'secrets.json'),'utf8'));
  const r=await fetch(new URL('/api/notify',url),{method:'POST',redirect:'error',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},body:JSON.stringify({thread_id:config.pocket.threadId,title:n.title,message:n.message,kind:'question'}),signal:AbortSignal.timeout(30000)});
  if(!r.ok)throw Error('Pocket notification failed');
 };
 try{await tick({gh,config,state,save,notify});delete state.lastError;save();}
 catch(e){
  const reason=e.message||'Worker error';console.error(reason);
  if(config.notifyErrors===true&&(!state.lastError||Date.now()-state.lastError.at>6*3600000)){
   try{await notify({title:'Pocket maintainer needs attention',message:'Automatic PR processing paused for this run. Check the local pocket-maintainer service log. No failed review is treated as approval.'});state.lastError={at:Date.now()};save();}catch{}
  }
  process.exitCode=1;
 }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)await main();
