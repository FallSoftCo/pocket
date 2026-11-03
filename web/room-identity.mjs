const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
/** Authenticated roster/inbox metadata only; never infer a host from name/UUID. */
export function roomActorLabel(actor){
 const name=typeof actor?.name==='string'&&actor.name.trim()?actor.name:actor?.id||actor?.agentId||'Unknown agent';
 const identity=actor?.identity||actor,host=identity?.host;
 const bound=identity?.version===1&&identity.agentId===(actor?.id||actor?.agentId);
 return bound&&actor.kind==='agent'&&uuid(host?.id)&&typeof host.name==='string'&&host.name.trim()?(name.toLocaleLowerCase().includes(host.name.toLocaleLowerCase())?name:name+' · '+host.name):name;
}
export const roomActorNames=actors=>Object.fromEntries((actors||[]).map(a=>[a.id,roomActorLabel(a)]));
export function roomOriginSummary(detail,{available=true}={}){
 if(!available)return 'Room agent status unavailable';
 const state=detail?.roomAgents;
 if(state?.version!==1||!Array.isArray(state.agents))return 'Room participants';
 if(state.initializationRequired===false)return 'Room participants';
 if(!state.agents.length)return 'Room agents not initialized';
 const ready=state.agents.filter(a=>a.ready===true).length;
 return 'Room agents: '+ready+'/'+state.agents.length+' ready'+(state.generation?.phase==='staging'?' · Handoff required':ready<state.agents.length?' · Initialization incomplete':'');
}
export const roomOriginRows=(detail,{available=true}={})=>(detail?.roomAgents?.agents||[]).map(a=>
 (a.host_name||a.node)+' · '+(!available?'Status unavailable':a.ready===true?'Ready':a.readiness||a.state||'unconfirmed')+(!available||a.ready===true?'':a.note?' · '+a.note:''));

export function roomOriginPendingState(room){
 if((room?.room||room)?.room_kind==='global'||room?.roomAgents===undefined)return null;
 const setup=room.roomAgents,unknown={label:'Room agent status unavailable',tone:'unavailable',known:false};
 if(setup?.version!==1||!Array.isArray(setup.agents)||setup.agents.some(a=>!a||typeof a.ready!=='boolean'||!uuid(a.agent)||!uuid(a.node)))return unknown;
 const selected=(room?.room||room)?.id;
 if(selected&&setup.roomId!==selected||new Set(setup.agents.map(a=>a.agent)).size!==setup.agents.length||new Set(setup.agents.map(a=>a.node)).size!==setup.agents.length)return unknown;
 if(setup.initializationRequired===false)return null;
 if(!setup.agents.length)return {label:'Agents not initialized',tone:'busy',known:true};
 const phase=setup.generation?.phase;
 if(!['active','staging'].includes(phase))return unknown;
 if(setup.agents.some(a=>['blocked','unknown'].includes(a.readiness||a.state)))return {label:'Agent setup blocked',tone:'attention',known:true};
 const ready=setup.agents.filter(a=>a.ready).length,total=setup.agents.length;
 return ready<total?{label:'Preparing agents · '+ready+'/'+total,tone:'busy',known:true}
  :phase==='staging'?{label:'Agent setup · '+ready+'/'+total,tone:'busy',known:true}:null;
}
export const currentRoomActors=actors=>(actors||[]).filter(a=>a.can_read===true&&a.participation!=='retained-source-owner');
