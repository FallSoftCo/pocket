const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
/** Authenticated roster/inbox metadata only; never infer a host from name/UUID. */
export function roomActorLabel(actor){
 const name=typeof actor?.name==='string'&&actor.name.trim()?actor.name:actor?.id||actor?.agentId||'Unknown agent';
 const identity=actor?.identity||actor,host=identity?.host;
 const bound=identity?.version===1&&identity.agentId===(actor?.id||actor?.agentId);
 return bound&&actor.kind==='agent'&&uuid(host?.id)&&typeof host.name==='string'&&host.name.trim()?name+' · '+host.name:name;
}
export const roomActorNames=actors=>Object.fromEntries((actors||[]).map(a=>[a.id,roomActorLabel(a)]));
export function roomOriginSummary(detail,{available=true}={}){
 if(!available)return 'Room agent status unavailable';
 const state=detail?.roomAgents;
 if(state?.version!==1||!Array.isArray(state.agents))return 'Room participants';
 if(!state.agents.length)return 'Room agents not initialized';
 const ready=state.agents.filter(a=>a.ready===true).length;
 return 'Room agents: '+ready+'/'+state.agents.length+' ready'+(state.generation?.phase==='staging'?' · Handoff required':ready<state.agents.length?' · Initialization incomplete':'');
}
export const roomOriginRows=detail=>(detail?.roomAgents?.agents||[]).map(a=>
 (a.host_name||a.node)+' · '+(a.ready===true?'ready':a.readiness||a.state||'unconfirmed')+(a.ready===true?'':a.note?' · '+a.note:''));
