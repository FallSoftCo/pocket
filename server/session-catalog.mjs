// Runtime identity and work recency, independent of metadata inspection timestamps.
export const milliseconds=value=>Number(value)>0?(Number(value)<1e11?Number(value)*1000:Number(value)):0;
export function sessionIdentity(thread){
  let source=thread.source;try{if(typeof source==='string'&&source.startsWith('{'))source=JSON.parse(source);}catch{}
  const agent=source?.subAgent||source?.subagent;
  const spawn=agent?.thread_spawn||agent?.threadSpawn;
  const parentThreadId=thread.parentThreadId||spawn?.parent_thread_id||spawn?.parentThreadId||null;
  const isChild=!!parentThreadId||!!agent||(typeof source==='string'&&/^subAgent/i.test(source));
  return {parentThreadId,isChild,agentNickname:thread.agentNickname||spawn?.agent_nickname||null,
    agentRole:thread.agentRole||spawn?.agent_role||null,
    canAcceptDirectInput:isChild?thread.canAcceptDirectInput===true:thread.canAcceptDirectInput!==false};
}
export function sessionWorkTime(thread){
  return Math.max(milliseconds(thread.recencyAt),Number(thread.activityAt)||0,milliseconds(thread.createdAt));
}
export function catalogFields(thread){
  return {...sessionIdentity(thread),createdAt:thread.createdAt,recencyAt:sessionWorkTime(thread)};
}
export function compareSessionWork(a,b){return sessionWorkTime(b)-sessionWorkTime(a)||a.id.localeCompare(b.id);}
export function requireDirectSessionInput(thread){
  const identity=sessionIdentity(thread);
  if(!identity.canAcceptDirectInput){const error=Error(identity.parentThreadId?'This is a delegated agent. Open its parent task to send guidance; direct replies are not supported by this runtime.':'Direct replies are unavailable for this agent. Review its results from the owning task.');error.status=409;throw error;}
}
