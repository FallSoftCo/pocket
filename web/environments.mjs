import {sessionIdentity} from './model.mjs';
export function environmentOf(scope){
 return /^env_([a-z][a-z0-9-]{1,30})__[A-Za-z0-9_-]+$/.exec(String(scope))?.[1]||null;
}
export function environmentPath(path,scope){
 const environment=environmentOf(scope);
 return environment&&!path.startsWith('/api/environments/')?'/api/environments/'+environment+path.slice(4):path;
}
const hasWork=thread=>['active','pending'].includes(thread.status?.type)||thread.pendingCount>0||!!thread.preview?.trim();
export function catalogVisible(thread,filter,query=''){
 const busy=hasWork(thread);
 if(filter==='empty')return thread.catalogEmpty===true&&!busy;
 if(filter==='temporary')return thread.ephemeral===true;
 if(!query&&((thread.catalogEmpty&&!busy)||(thread.ephemeral&&!sessionIdentity(thread).isChild)))return false;
 return filter!=='active'||['active','pending'].includes(thread.status?.type);
}
export function catalogTitle(thread){
 if(thread.displayName)return thread.displayName;
 if(thread.name&&!/^(?:untitled(?: task| work)?|unnamed(?: session)?|new task)$/i.test(thread.name.trim()))return thread.name;
 if(thread.agentNickname)return thread.agentNickname;
 if(thread.preview?.trim())return thread.preview.replace(/\s+/g,' ').trim().slice(0,90);
 const folder=String(thread.cwd||'').split(/[\\/]/).filter(Boolean).at(-1);
 return (thread.catalogEmpty&&!hasWork(thread)?'Not started':thread.ephemeral?'Temporary work':thread.status?.type==='pending'?'Starting':'Session')+(folder?' · '+folder:'');
}
