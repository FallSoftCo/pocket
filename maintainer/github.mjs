import {digest,snapshotKey} from './core.mjs';
export class GitHub {
 constructor(token,repo='FallSoftCo/pocket'){this.token=token;this.repo=repo;this.root=`/repos/${repo}`;}
 async request(path,{method='GET',body}={}){
  if(!path.startsWith(this.root+'/')&&path!==this.root)throw Error('Repository-scoped API path required');
  const r=await fetch('https://api.github.com'+path,{method,redirect:'error',headers:{Authorization:`Bearer ${this.token}`,Accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
  const raw=await r.text();if(!r.ok)throw Object.assign(Error(`GitHub ${method} returned ${r.status}`),{status:r.status});
  if(raw.length>5000000)throw Error('GitHub response exceeds context limit');
  return raw?JSON.parse(raw):null;
 }
 async file(path,ref,expectedSha=null){
  const data=await this.request(`${this.root}/contents/${path.split('/').map(encodeURIComponent).join('/')}?ref=${encodeURIComponent(ref)}`);
  if(data.type!=='file'||data.encoding!=='base64'||data.size>200000)throw Error('File exceeds review context limits');
  if(expectedSha&&data.sha!==expectedSha)throw Error('Changed file content identity mismatch');
  return Buffer.from(data.content,'base64').toString('utf8');
 }
 async snapshot(number){
  if(!Number.isSafeInteger(number)||number<1)throw Error('Invalid pull request number');
  const p=await this.request(`${this.root}/pulls/${number}`);
  if(p.base.repo.full_name.toLowerCase()!==this.repo.toLowerCase()||p.base.ref!=='main')throw Error('Only the configured repository main branch is supported');
  const base=p.base.sha;
  const [rawPolicy,principles]=await Promise.all([this.file('maintainer/policy.json',base),this.file('MAINTAINER_POLICY.md',base)]);
  const policy=JSON.parse(rawPolicy);
  if(policy.repository.toLowerCase()!==this.repo.toLowerCase()||policy.version!==1)throw Error('Unsupported maintainer policy');
  let files=await this.request(`${this.root}/pulls/${number}/files?per_page=100`);
  let incomplete=files.length!==p.changed_files||files.length>policy.maxFiles;
  let budget=0;
  if(!incomplete)for(const f of files){
   budget+=Buffer.byteLength(f.patch||'');
   if(!f.patch||budget>policy.maxContextBytes){incomplete=true;continue;}
   try{f.context=await this.file(f.status==='removed'?(f.previous_filename||f.filename):f.filename,f.status==='removed'?base:p.head.sha,f.sha);budget+=Buffer.byteLength(f.context);if(f.context.includes('\0')||budget>policy.maxContextBytes){delete f.context;incomplete=true;}}
   catch{incomplete=true;}
  }
  // Reopening a closed PR explicitly requests reconsideration. Comments are never prompts.
  const events=await this.pages(`${this.root}/issues/${number}/events`);
  const reopened=events.filter(e=>e.event==='reopened').map(e=>e.id).at(-1)||0;
  const s={number,state:p.state,draft:p.draft,title:p.title,body:p.body||'',head:p.head.sha,base,behind:p.mergeable_state==='behind',maintainable:p.maintainer_can_modify||p.head.repo?.full_name?.toLowerCase()===this.repo.toLowerCase(),author:p.user.login,labels:p.labels.map(x=>x.name),url:p.html_url,files,incomplete,policy,principles,policyHash:digest({policy,principles}),reopened};
  s.key=snapshotKey(s);return s;
 }
 async checksPass(s){
  const runs=await this.request(`${this.root}/actions/workflows/check.yml/runs?event=pull_request&head_sha=${s.head}&per_page=20`);
  const run=runs.workflow_runs.find(r=>r.event==='pull_request'&&r.head_sha===s.head&&r.pull_requests.some(p=>p.number===s.number));
  if(!run||run.status!=='completed'||run.conclusion!=='success')return false;
  const jobs=await this.request(`${this.root}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`);
  return s.policy.requiredChecks.every(name=>jobs.jobs.some(j=>j.name===name&&j.status==='completed'&&j.conclusion==='success'));
 }
 async prepareCI(s){
  // Called only after policy/code approval. Never approve a modified workflow.
  if(s.files.some(f=>[f.filename,f.previous_filename].filter(Boolean).some(p=>p.startsWith('.github/'))))return false;
  const fresh=await this.snapshot(s.number);
  if(fresh.key!==s.key||fresh.state!=='open'||fresh.draft||fresh.labels.includes('maintainer:hold'))return false;
  if(fresh.behind&&fresh.maintainable){
   await this.request(`${this.root}/pulls/${s.number}/update-branch`,{method:'PUT',body:{expected_head_sha:s.head}});
   return false; // The updated commit needs fresh review and CI.
  }
  const runs=await this.request(`${this.root}/actions/workflows/check.yml/runs?event=pull_request&head_sha=${s.head}&per_page=20`);
  const run=runs.workflow_runs.find(r=>r.head_sha===s.head&&r.event==='pull_request'&&r.pull_requests.some(p=>p.number===s.number));
  if(run&&(run.status==='action_required'||run.conclusion==='action_required'))await this.request(`${this.root}/actions/runs/${run.id}/approve`,{method:'POST'});
  return true;
 }
 async pages(path){
  const all=[];
  for(let page=1;page<=10;page++){
   const rows=await this.request(`${path}${path.includes('?')?'&':'?'}per_page=100&page=${page}`);
   if(!Array.isArray(rows))throw Error('Expected paginated GitHub records');
   all.push(...rows);if(rows.length<100)return all;
  }
  throw Error('GitHub history exceeds automatic processing limit');
 }
 async comments(number){return this.pages(`${this.root}/issues/${number}/comments`);}
}
