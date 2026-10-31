/** Canonical Hollywood data, independent of the gateway used to read it.
 * Execution observations stay operations; they never become agent chat replies.
 */
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
const string=v=>typeof v==='string'?v:'';
const terminal=new Set(['completed','failed','declined']);
export function roomActivityState(activity){
 const keys=['pending','claimed','running','cancelRequested','unknown','waitingForHuman'];
 if(!activity||activity.version!==1||!Number.isSafeInteger(activity.observedAt)||activity.observedAt<=0||keys.some(k=>!Number.isSafeInteger(activity[k])||activity[k]<0))
  return {label:'Status unavailable',tone:'unavailable',known:false};
 // Human attention overlaps delivery counts. Never add it to an execution total.
 const labels=[];
 if(activity.waitingForHuman)labels.push(activity.waitingForHuman+' needs you');
 for(const [key,label]of [['running','running'],['claimed','starting'],['pending','queued'],['cancelRequested','stopping'],['unknown','unconfirmed']])if(activity[key])labels.push(activity[key]+' '+label);
 return {label:labels.join(' · ')||'Idle',tone:activity.waitingForHuman||activity.unknown?'attention':labels.length?'live':'idle',known:true};
}
export function roomState(room,{available=true,now=Date.now()}={}){
 const unavailable={label:'Status unavailable',tone:'unavailable',known:false};
 if(!available)return unavailable;
 if(!Object.hasOwn(room||{},'nativeState'))return roomActivityState(room?.activity);
 const n=room.nativeState;
 if(!n||n.version!==1||!Number.isSafeInteger(n.observedAt)||n.observedAt<=0||!Number.isSafeInteger(n.runtimeCount)||n.runtimeCount<0||!Array.isArray(n.agents)||n.agents.length>n.runtimeCount)return unavailable;
 if(n.runtimeCount===0)return n.agents.length===0?roomActivityState(room.activity):unavailable;
 let working=0,waiting=0,unknown=n.runtimeCount-n.agents.length;const seen=new Set();
 for(const agent of n.agents){
  const key=agent.agentId+'/'+agent.nativeThread;
  if(!uuid(agent.agentId)||!agent.nativeThread||seen.has(key)||!Number.isSafeInteger(agent.expiresAt))return unavailable;
  seen.add(key);
  if(agent.expiresAt<=Math.max(now,n.observedAt)){unknown++;continue;}
  if(agent.status?.type==='idle')continue;
  if(agent.status?.type==='active'){
   const flags=agent.status.activeFlags;
   if(!Array.isArray(flags)||flags.some(f=>!['waitingOnApproval','waitingOnUserInput'].includes(f)))return unavailable;
   if(flags.length)waiting++;else working++;
  }else if(['unavailable','notLoaded','systemError'].includes(agent.status?.type))unknown++;
  else return unavailable;
 }
 const labels=[waiting?waiting+' needs you':'',working?working+' working':'',unknown?unknown+' unavailable':''].filter(Boolean);
 return {label:labels.join(' · ')||'Idle',tone:waiting||unknown?'attention':working?'live':'idle',known:true,native:true};
}
export function roomEventRows(events,{authorityId,roomId,principalId,names={}}){
 if(![authorityId,roomId,principalId].every(uuid))throw Error('A verified room audience is required.');
 const unique=new Map();
 for(const event of events||[]){
  if(!uuid(event?.id)||event.authority!==authorityId||event.room!==roomId||!Number.isSafeInteger(event.seq)||event.seq<0)throw Error('Room history identity is invalid.');
  const old=unique.get(event.id);if(old&&JSON.stringify(old)!==JSON.stringify(event))throw Error('Canonical room event changed.');
  unique.set(event.id,event);
 }
 const rows=[],operations=new Map();
 for(const event of [...unique.values()].sort((a,b)=>a.seq-b.seq)){
  const data=event.data||{},native=data.type==='executionActivity';
  const actionId=native&&data.activityVersion===1&&typeof data.actionId==='string'&&data.actionId.length>0&&data.actionId.length<=256?data.actionId:null;
  const identity=actionId?JSON.stringify([authorityId,roomId,event.author,actionId]):JSON.stringify([authorityId,event.author,data.deliveryId,data.generation,data.thread,data.turn,data.itemId]);
  const structured=native&&['planUpdate','goalState'].includes(data.nativeType);
  const activity=native&&!structured;
  const status=data.phase==='completed'?(terminal.has(data.status)?data.status:'completed'):data.phase==='started'?'inProgress':string(data.status)||'unknown';
  const previous=native?operations.get(identity):null;
  const observations=[...(previous?.actionObservations||[]),...(native?[{eventId:event.id,seq:event.seq,phase:data.phase,status:data.status}]:[])];
  if(previous&&terminal.has(previous.status)&&!terminal.has(status)){previous.actionObservations=observations;continue;}
  const row={id:native?'room-action:'+identity:'room-event:'+event.id,roomEventId:event.id,roomAuthor:event.author===principalId?'You':string(event.authorName)||names[event.author]||event.author,
   roomAudience:event.audience,roomRecipient:event.recipient,kind:activity||structured?'activity':event.author===principalId?'user':'message',text:string(event.body),canonicalSeq:event.seq};
  if(native){
   Object.assign(row,{type:structured?data.nativeType:'roomOperation',status,actionId,actionObservations:observations,agentId:event.author,hostId:data.hostId,deliveryId:data.deliveryId,nativeThread:data.thread,nativeTurn:data.turn,itemId:data.itemId,
    title:string(data.title)||'Run',groupBoundary:JSON.stringify([authorityId,roomId,event.audience,event.audience==='direct'?[event.author,event.recipient].sort():[]]),
    text:string(['agentMessage','plan'].includes(data.nativeType)?data.output:data.command)||string(event.body),
    detail:[string(data.output),...(Array.isArray(data.changes)?data.changes.map(change=>string(change.path)+'\n'+string(change.diff)):[]),data.cwd?'Directory: '+data.cwd:'',
     'Agent: '+event.author+'\nHost: '+string(data.hostId)+'\nExecution: '+string(data.deliveryId)+'\nThread: '+string(data.thread)+'\nTurn: '+string(data.turn)+'\nEvent: '+event.id].filter(Boolean).join('\n\n'),truncated:data.outputTruncated===true||data.detailsTruncated===true});
   if(structured&&Array.isArray(data.plan))row.text=data.plan.map(step=>string(step.status)+' · '+string(step.step)).join('\n');
   if(Number.isSafeInteger(data.exitCode))row.exitCode=data.exitCode;
   if(Number.isSafeInteger(data.durationMs))row.durationMs=data.durationMs;
   if(previous){const at=rows.indexOf(previous);rows[at]=row;}else rows.push(row);
   operations.set(identity,row);
  }else rows.push(row);
 }
 return rows;
}

/** One canonical event owner per verified audience and view. Page cursors are
 * transport cursors, never timestamps or inferred offsets. */
export class RoomHistory {
 constructor(scope,{view='all'}={}){
  if(!['all','conversation','activity'].includes(view))throw Error('Invalid room history view.');
  if(![scope.authorityId,scope.roomId,scope.principalId].every(uuid))throw Error('A verified room audience is required.');
  this.scope=Object.freeze({...scope});this.view=view;this.events=new Map();this.olderCursor=null;this.latestSeq=null;this.initialized=false;
 }
 apply(page,{older=false}={}){
  if(page?.authorityId!==this.scope.authorityId||page?.roomId!==this.scope.roomId||page?.view!==this.view||!Array.isArray(page.events))throw Error('Room history scope changed.');
  roomEventRows(page.events,this.scope); // Validate the entire page before mutating state.
  const next=new Map(this.events);
  for(const event of page.events){
   const old=next.get(event.id);
   if(old&&JSON.stringify(old)!==JSON.stringify(event))throw Error('Canonical room event changed.');
   next.set(event.id,event);
  }
  // A conflicting sequence also indicates a corrupt or misrouted page.
  const sequences=new Map();
  for(const event of next.values()){
   if(sequences.has(event.seq)&&sequences.get(event.seq)!==event.id)throw Error('Canonical room sequence changed.');
   sequences.set(event.seq,event.id);
  }
  const cursor=page.olderCursor??null;
  if(cursor!==null&&(!Number.isSafeInteger(cursor)||cursor<0))throw Error('Invalid room history cursor.');
  this.events=next;
  if(older||!this.initialized)this.olderCursor=cursor;
  this.initialized=true;
  for(const seq of sequences.keys())this.latestSeq=this.latestSeq===null?seq:Math.max(this.latestSeq,seq);
  return this.rows();
 }
 rows(){return roomEventRows([...this.events.values()],this.scope);}
}
