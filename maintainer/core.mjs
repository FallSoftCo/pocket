import {createHash} from 'node:crypto';
export const digest=x=>createHash('sha256').update(typeof x==='string'?x:JSON.stringify(x)).digest('hex');
export const schema={type:'object',additionalProperties:false,required:['verdict','summary','findings','policyRule','evidence','risk'],properties:{verdict:{type:'string',enum:['approve','changes','decline','owner']},summary:{type:'string'},findings:{type:'array',items:{type:'string'}},policyRule:{type:'string'},evidence:{type:'array',items:{type:'object',additionalProperties:false,required:['path','quote'],properties:{path:{type:'string'},quote:{type:'string'}}}},risk:{type:'string',enum:['low','medium','high']}}};
export function validateReview(r){
 if(!r||!['approve','changes','decline','owner'].includes(r.verdict)||!['low','medium','high'].includes(r.risk)||typeof r.summary!=='string'||r.summary.length<8||r.summary.length>2500||typeof r.policyRule!=='string'||!Array.isArray(r.findings)||r.findings.length>12||r.findings.some(f=>typeof f!=='string'||f.length>1500)||!Array.isArray(r.evidence)||r.evidence.length>8||r.evidence.some(e=>typeof e.path!=='string'||typeof e.quote!=='string'||e.quote.length>500))throw Error('Invalid reviewer response');
 return r;
}
export function snapshotKey(s){return digest({number:s.number,head:s.head,base:s.base,title:s.title,body:s.body,policy:s.policyHash,files:s.files.map(f=>({path:f.filename,sha:f.sha,status:f.status,patch:f.patch})),reopened:s.reopened,clarifications:s.clarifications||[]});}
export const addedLines=f=>(f.patch||'').split('\n').filter(l=>l.startsWith('+')&&!l.startsWith('+++')).map(l=>l.slice(1)).join('\n');
export function sensitive(s){return s.files.some(f=>[f.filename,f.previous_filename].filter(Boolean).some(p=>s.policy.protectedPaths.includes(p)||s.policy.protectedPrefixes.some(x=>p.startsWith(x))||/(^|\/)(AGENTS\.md|.*\.rules|.*\.gradle\.kts|.*\.pem|.*\.keystore|.*\.jks)$/.test(p)||/Pocket(State|Firebase|Service|Attention|Audio)\.kt$/.test(p)||p.startsWith('android/gradle/')));}
export function eligible(s){
 const lines=s.files.reduce((n,f)=>n+f.additions+f.deletions,0);
 if(s.incomplete||s.files.length===0||s.files.length>s.policy.maxFiles||lines>s.policy.maxChangedLines)return false;
 return s.files.every(f=>typeof f.patch==='string'&&['modified','added','removed'].includes(f.status)&&!f.filename.includes('..')&&!f.filename.includes('\\'));
}
export function proseOnly(s){
 if(sensitive(s)||s.files.some(f=>!s.policy.autoMergePaths.includes(f.filename)||f.status!=='modified'))return false;
 if(s.files.reduce((n,f)=>n+f.additions+f.deletions,0)>s.policy.autoMergeMaxChangedLines)return false;
 // Commands, links, directives, code fences and configuration require owner review.
 const risky=/[`<>\[\]{}\\$=]|https?:|\b(?:curl|wget|sudo|chmod|npm|node|pip|apt|export|eval|exec|token|password|secret|credential|approval|sandbox|permission|install|Firebase|FCM|Codex|MCP)\b/i;
 return s.files.every(f=>f.patch.split('\n').filter(l=>/^[+-]/.test(l)&&!/^\+\+\+|^---/.test(l)).every(l=>!risky.test(l.slice(1))));
}
export function evidenceValid(s,r){return !!s.policy.declineRules[r.policyRule]&&r.evidence.length>0&&r.evidence.every(e=>e.quote.length>=12&&s.files.some(f=>f.filename===e.path&&addedLines(f).includes(e.quote)));}
export function decide(s,r,second=null){
 validateReview(r);if(second)validateReview(second);
 if(!eligible(s))return {action:'owner',reason:'The change needs a fuller review than the automatic context limits allow.'};
 // The worker and its own policy cannot adjudicate changes to their authority.
 if(s.files.some(f=>f.filename==='MAINTAINER_POLICY.md'||f.filename.startsWith('maintainer/')||f.filename.startsWith('.github/')))return {action:'owner',reason:'Maintainer policy or automation changes need the owner.'};
 if(r.verdict==='decline'){
  if(second?.verdict==='decline'&&second.policyRule===r.policyRule&&evidenceValid(s,r)&&evidenceValid(s,second))return {action:'decline',reason:r.summary};
  return {action:'owner',reason:'A scope rejection was not independently corroborated.'};
 }
 if(r.verdict==='changes'&&r.findings.length)return {action:'changes',reason:r.summary};
 if(r.verdict==='approve'&&r.findings.length===0){
  if(r.risk==='low'&&proseOnly(s))return {action:'merge',reason:r.summary};
  if(second?.verdict==='changes'&&second.findings.length)return {action:'changes',reason:second.summary,findings:second.findings};
  if(second?.verdict==='approve'&&second.findings.length===0)return {action:'merge',reason:r.summary};
  return {action:'owner',reason:'This change needs two agreeing reviews before automatic merging; agreement is not yet established.'};
 }
 return {action:'owner',reason:r.summary};
}
export function publicText(text){
 return String(text).replace(/<!--[\s\S]*?-->/g,'').replace(/@/g,'＠').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g,'').slice(0,6000);
}
export function secretLike(s){return /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{35,}|sk-(?:proj-)?[A-Za-z0-9_-]{35,})\b|"private_key"\s*:\s*"/.test(JSON.stringify(s.files));}
export function makeComment(s,r,d,key){
 const footer=`\n\nAutomated review by Pocket Maintainer · commit \`${s.head.slice(0,12)}\`. Maintainers can override this decision. [Contribution policy](https://github.com/${s.policy.repository}/blob/main/MAINTAINER_POLICY.md).\n<!-- pocket-maintainer:${key} -->`;
 const intro={merge:'This fits Pocket’s direction and qualifies for automatic merging after the required checks pass.',approve:'This fits Pocket’s direction. I recommend approval; a maintainer will handle the merge.',changes:'This fits the project, but the following changes need attention.',decline:'Thank you for the contribution. This takes Pocket in a direction outside the contribution policy, so I’m closing it upstream.',owner:'This needs a maintainer decision. I’m leaving the pull request open.'}[d.action];
 let body=`${intro}\n\n${publicText(d.reason)}`;
 if(d.action==='changes')body+='\n\n'+(d.findings||r.findings).map(f=>'- '+publicText(f)).join('\n');
 if(d.action==='decline')body+=`\n\nPolicy: **${r.policyRule}** — ${s.policy.declineRules[r.policyRule]}\n\nYou’re welcome to maintain this direction in a fork under the MIT license. If I’ve misunderstood the change, comment with \`/pocket reconsider <reason>\` for a fresh review (up to three clarification comments per PR).`;
 return body+footer;
}
