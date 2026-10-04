export async function reconsider(gh,queue,id){
 const c=await gh.request(`${gh.root}/issues/comments/${id}`);
 const match=c.issue_url?.match(/\/issues\/([1-9][0-9]*)$/);if(!match)return;
 const n=Number(match[1]);let s=await gh.snapshot(n);
 if(s.merged||s.labels.includes('maintainer:hold')||!s.clarifications.some(x=>x.id===c.id))return;
 const marker=`<!-- pocket-reconsider:${c.id}:${c.updated_at} -->`;
 const comments=await gh.comments(n);
 if(s.state==='closed'){
  const events=await gh.pages(`${gh.root}/issues/${n}/events`);
  const closed=events.filter(e=>e.event==='closed'||e.event==='reopened').at(-1);
  const receipt=comments.find(x=>x.user?.login==='github-actions[bot]'&&x.body.includes('<!-- pocket-maintainer:')&&x.body.includes('so I’m closing it upstream.'));
  if(closed?.event!=='closed'||closed.actor?.login!=='github-actions[bot]'||!receipt||!s.labels.includes('maintainer:out-of-scope'))return;
  // Check again immediately before reopening; a human close/hold wins.
  const fresh=await gh.snapshot(n);
  if(fresh.key!==s.key||fresh.labels.includes('maintainer:hold')||fresh.merged)return;
  await gh.request(`${gh.root}/pulls/${n}`,{method:'PATCH',body:{state:'open'}});
 }
 // Enqueue before acknowledgement so a crash cannot lose reconsideration.
 queue.enqueue(`pr:${n}`);
 if(!comments.some(x=>x.user?.type==='Bot'&&x.body.includes(marker)))await gh.request(`${gh.root}/issues/${n}/comments`,{method:'POST',body:{body:`I’ll review the change again with your clarification. The same contribution policy and required checks apply.\n\n${marker}`}});
}

export async function completedRun(gh,queue,id,alert){
 const r=await gh.request(`${gh.root}/actions/runs/${id}`);
 if(r.path!=='.github/workflows/check.yml'||r.status!=='completed')return;
 if(r.event==='pull_request'){
  let prs=r.pull_requests||[];
  if(!prs.length&&r.head_repository?.owner?.login&&r.head_branch){
   const head=encodeURIComponent(r.head_repository.owner.login+':'+r.head_branch);
   prs=await gh.pages(`${gh.root}/pulls?state=open&base=main&head=${head}`);
  }
  for(const p of prs){
   const live=await gh.request(`${gh.root}/pulls/${p.number}`);
   if(live.state==='open'&&live.base.ref==='main'&&live.head.sha===r.head_sha&&gh.matchesCI(r,{number:live.number,head:live.head.sha,headRepoId:live.head.repo?.id,headRef:live.head.ref}))queue.enqueue(`pr:${p.number}`);
  }
 }else if(['push','workflow_dispatch'].includes(r.event)&&r.head_branch==='main'&&r.conclusion!=='success'){
  await alert(`ci:${r.id}:${r.run_attempt}`,'NextComp main checks failed',`The main-branch check ended with ${r.conclusion}. Open GitHub Actions to inspect run ${r.id}.`);
 }
}

export async function postMerge(gh,queue,number,alert){
 const p=await gh.request(`${gh.root}/pulls/${number}`);
 if(!p.merged||p.base.ref!=='main'||! /^[a-f0-9]{40}$/.test(p.merge_commit_sha))return;
 const sha=p.merge_commit_sha,title=`Post-merge ${sha}`;
 const {workflow_runs:runs}=await gh.request(`${gh.root}/actions/workflows/check.yml/runs?event=workflow_dispatch&per_page=100`);
 const run=runs.find(r=>r.event==='workflow_dispatch'&&r.display_title===title&&r.head_branch==='main');
 if(run){
  if(run.status==='completed'&&run.conclusion!=='success')await alert(`ci:${run.id}:${run.run_attempt}`,'NextComp post-merge checks failed',`Checks for merged PR #${number} ended with ${run.conclusion}. Open GitHub Actions to inspect run ${run.id}.`);
  return run.status==='completed'?undefined:{retryAt:Date.now()+10*60000};
 }
 const receipt=queue.get(`postmerge:${sha}`);
 if(receipt?.at&&Date.now()-receipt.at<10*60000)return {retryAt:receipt.at+10*60000};
 if((receipt?.attempts||0)>=3)throw Error('Post-merge CI dispatch was not observed after three attempts');
 await gh.request(`${gh.root}/actions/workflows/check.yml/dispatches`,{method:'POST',body:{ref:'main',inputs:{commit:sha}}});
 queue.set(`postmerge:${sha}`,{at:Date.now(),attempts:(receipt?.attempts||0)+1});
 return {retryAt:Date.now()+60000};
}
