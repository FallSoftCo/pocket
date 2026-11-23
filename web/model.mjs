// Matches the session catalog workstream's conservative runtime capability semantics.
export const milliseconds=value=>Number(value)>0?(Number(value)<1e11?Number(value)*1000:Number(value)):0;
export function sessionIdentity(thread={}){
 thread=thread||{};
 let source=thread.source;try{if(typeof source==='string'&&source.startsWith('{'))source=JSON.parse(source);}catch{}
 const agent=source?.subAgent||source?.subagent,spawn=agent?.thread_spawn||agent?.threadSpawn;
 const parentThreadId=thread.parentThreadId||spawn?.parent_thread_id||spawn?.parentThreadId||null;
 const isChild=thread.isChild===true||!!parentThreadId||!!agent||(typeof source==='string'&&/^subAgent/i.test(source));
 return {parentThreadId,isChild,canAcceptDirectInput:isChild?thread.canAcceptDirectInput===true:thread.canAcceptDirectInput!==false,agentNickname:thread.agentNickname||spawn?.agent_nickname||null,agentRole:thread.agentRole||spawn?.agent_role||null};
}
export const workTime=t=>Math.max(milliseconds(t.recencyAt),milliseconds(t.activityAt),milliseconds(t.createdAt));
export const compareWork=(a,b)=>workTime(b)-workTime(a)||a.id.localeCompare(b.id);
export function mergeRows(previous,incoming){const rows=new Map(previous.map(r=>[r.id,r]));for(const row of incoming){const old=rows.get(row.id);if(!old||(row.version||0)>=(old.version||0))rows.set(row.id,row);}return [...rows.values()];}
export function pendingFor(scope,text,mode='auto'){return {id:crypto.randomUUID(),scope,text,mode,createdAt:Date.now()};}
