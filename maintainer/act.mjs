import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {GitHub} from './github.mjs';
import {decide,makeComment,secretLike,validateReview} from './core.mjs';
export async function act(gh,input){
 const number=Number(input.pr);if(!Number.isSafeInteger(number)||number<1||!/^[a-f0-9]{40}$/.test(input.head)||!/^[a-f0-9]{40}$/.test(input.base)||!/^[a-f0-9]{64}$/.test(input.key))throw Error('Invalid immutable review identifiers');
 const {review,second=null}=typeof input.result==='string'?JSON.parse(input.result):input.result;
 validateReview(review);if(second)validateReview(second);
 let s=await gh.snapshot(number);
 const current=()=>s.state==='open'&&!s.draft&&!s.labels.includes('maintainer:hold')&&s.head===input.head&&s.base===input.base&&s.key===input.key;
 if(!current())return {state:'stale-or-held'};
 let decision=decide(s,review,second);
 if(secretLike(s))decision={action:'owner',reason:'Potential sensitive material needs private maintainer review. No content is quoted here.'};
 const marker=`<!-- pocket-maintainer:${s.key} -->`;
 const comments=await gh.comments(number);
 const prior=comments.find(c=>c.user.login==='github-actions[bot]'&&c.body.includes(marker));
 const body=makeComment(s,review,decision,s.key);
 // Re-fetch immediately before writing, never act on an old revision or new hold.
 s=await gh.snapshot(number);if(!current())return {state:'stale-or-held'};
 // A required GitHub check records approval without needing organization-wide
 // permission for Actions to submit approving PR reviews.
 const checks=await gh.request(`${gh.root}/commits/${s.head}/check-runs?check_name=Pocket%20review&per_page=100`);
 const existing=checks.check_runs.find(c=>c.app?.slug==='github-actions'&&c.external_id===s.key);
 const conclusion=decision.action==='merge'?'success':'action_required';
 if(!existing||existing.conclusion!==conclusion){
  const result={name:'Pocket review',head_sha:s.head,status:'completed',conclusion,external_id:s.key,output:{title:decision.action==='merge'?'Approved by NextComp Maintainer':'NextComp Maintainer: '+decision.action,summary:body}};
  await gh.request(existing?`${gh.root}/check-runs/${existing.id}`:`${gh.root}/check-runs`,{method:existing?'PATCH':'POST',body:result});
 }
 if(!prior){
  await gh.request(`${gh.root}/issues/${number}/comments`,{method:'POST',body:{body}});
 }
 const label={owner:'maintainer:owner',approve:'maintainer:owner',changes:'maintainer:changes',decline:'maintainer:out-of-scope',merge:'maintainer:ready'}[decision.action];
 await gh.request(`${gh.root}/issues/${number}/labels`,{method:'POST',body:{labels:[label]}});
 for(const old of s.labels.filter(l=>['maintainer:owner','maintainer:changes','maintainer:out-of-scope','maintainer:ready'].includes(l)&&l!==label)){
  try{await gh.request(`${gh.root}/issues/${number}/labels/${encodeURIComponent(old)}`,{method:'DELETE'});}catch(e){if(e.status!==404)throw e;}
 }
 if(decision.action==='decline'){
  s=await gh.snapshot(number);if(!current())return {state:'stale-or-held'};
  await gh.request(`${gh.root}/pulls/${number}`,{method:'PATCH',body:{state:'closed'}});return {state:'closed'};
 }
 if(decision.action==='merge'&&await gh.checksPass(s)){
  s=await gh.snapshot(number);if(!current())return {state:'stale-or-held'};
  // Required CI and commit-bound review checks remain enforced by GitHub.
  const r=await gh.request(`${gh.root}/pulls/${number}/merge`,{method:'PUT',body:{sha:s.head,merge_method:'squash'}});
  return {state:r.merged?'merged':'waiting-for-checks'};
 }
 return {state:decision.action==='merge'?'waiting-for-checks':decision.action};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const event=JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH,'utf8'));
 if(process.env.GITHUB_EVENT_NAME!=='workflow_dispatch'||process.env.GITHUB_REF!=='refs/heads/main'||process.env.GITHUB_REPOSITORY?.toLowerCase()!=='fallsoftco/pocket')throw Error('Only trusted main-branch dispatches are allowed');
 const gh=new GitHub(process.env.GITHUB_TOKEN);
 console.log(JSON.stringify(await act(gh,event.inputs)));
}
