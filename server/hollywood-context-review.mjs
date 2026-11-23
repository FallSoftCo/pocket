/** Opt-in paired-human context review; no connector, native RPC or arbitrary proxy. */
const fail=(status,message,code='HOLLYWOOD_REQUEST_REJECTED')=>Object.assign(Error(message),{status,code});
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
const op=v=>typeof v==='string'&&/^[A-Za-z0-9_:-]{1,128}$/.test(v);
const hash=v=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v);
const revision=v=>Number.isSafeInteger(v)&&v>=1;
function exact(value,keys){
 return value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(k=>Object.hasOwn(value,k));
}
export const contextReviewPath=tail=>/^\/?agents\/[^/]+\/(?:context-system|contexts\/[^/]+\/seed-(?:sources|drafts)(?:\/.*)?)$/.test(tail);
export function contextReviewRequest(method,tail,query,body){
 const p=tail.split('/').filter(Boolean);let valid=false;
 if(!uuid(p[1])||p[0]!=='agents')throw fail(404,'Unknown context review route.');
 if(method==='GET'&&p.length===3&&p[2]==='context-system')valid=Object.keys(query).length===0;
 else if(p[2]==='contexts'&&p[3]==='writer'){
  if(method==='GET'&&p.length===5&&p[4]==='seed-sources'){
   if(Object.hasOwn(query,'reference'))valid=Object.keys(query).length===1&&typeof query.reference==='string'&&
    (query.reference==='role'||uuid(query.reference)||/^(?:goal|work):[0-9a-f-]{36}$/.test(query.reference)&&uuid(query.reference.split(':')[1]));
   else valid=Object.keys(query).every(k=>['view','after'].includes(k))&&
    (query.view===undefined||['active','history'].includes(query.view))&&
    (query.after===undefined||typeof query.after==='string'&&query.after.length>0&&query.after.length<=512);
  }else if(p[4]==='seed-drafts'&&Object.keys(query).length===0){
   if(p.length===5&&method==='POST')valid=exact(body,['id','operationId'])&&uuid(body.id)&&op(body.operationId);
   else if(uuid(p[5])){
    if(method==='GET'&&p.length===6)valid=true;
    else if(method==='GET'&&p[6]==='operations'&&p.length===8&&op(p[7]))valid=true;
    else if(method==='GET'&&p[6]==='control'&&(p.length===7||p.length===8&&p[7]==='content'))valid=true;
    else if(method==='PUT'&&p[6]==='marks'&&p.length===7){
     valid=exact(body,['operationId','revision','reference','sourceRevision','sourceSha256','keep','reason'])&&
      op(body.operationId)&&revision(body.revision)&&revision(body.sourceRevision)&&hash(body.sourceSha256)&&
      typeof body.keep==='boolean'&&typeof body.reason==='string'&&body.reason.length>0&&[...body.reason].length<=500&&
      (body.reference==='role'||uuid(body.reference)||/^(?:goal|work):[0-9a-f-]{36}$/.test(body.reference)&&uuid(body.reference.split(':')[1]));
    }else if(method==='POST'&&p[6]==='control'&&p.length===7&&body&&op(body.operationId)&&revision(body.revision)){
     const fields={prepare:['canonicalSha256'],approve:['approvalDigest'],cancel:[],revise:['replacementDraftId','canonicalSha256']}[body.action];
     valid=!!fields&&exact(body,['operationId','action','revision',...fields])&&
      (body.canonicalSha256===undefined||hash(body.canonicalSha256))&&
      (body.approvalDigest===undefined||hash(body.approvalDigest))&&
      (body.replacementDraftId===undefined||uuid(body.replacementDraftId));
    }
   }
  }
 }
 if(!valid||body!==undefined&&Buffer.byteLength(JSON.stringify(body))>14000)throw fail(400,'Use the original agent, draft, source revision and exact review action.');
 return {path:p.join('/'),method,query,...(['POST','PUT'].includes(method)?{body}:{})};
}
export function requireReviewDevice(req){
 if(!uuid(req.device?.id))throw fail(403,'Context review requires its paired human device.','HUMAN_DEVICE_REQUIRED');
}
export function verifyReviewReply(path,value,client){
 const p=path.split('/'),agent=p[1],draft=p[5];
 if(p[2]==='context-system'){
  if(value.system?.agentId!==agent||value.system?.authorityId!==client.config.authorityId)
   throw fail(502,'Context identity is unconfirmed.','HOLLYWOOD_OUTCOME_UNCONFIRMED');
 }else{
  const actualAgent=value.agentId||value.draft?.agent;
  const actualDraft=value.draftId||value.draft?.id;
  if(actualAgent&&actualAgent!==agent||draft&&actualDraft&&actualDraft!==draft)
   throw fail(502,'Original context review is unconfirmed.','HOLLYWOOD_OUTCOME_UNCONFIRMED');
 }
}
