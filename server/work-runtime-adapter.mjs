/** Qualified boundary candidate; no timer, enrollment, or production activation.
 * The durable workflow owns admission. This adapter only executes an already
 * persisted attempt and reconciles exact native client IDs. Runtime options are
 * inherited: no model, permission, original-request, or billing overrides.
 */
export class WorkRuntimeAdapter {
 constructor({work,runtime}) { Object.assign(this,{work,runtime});this.sending=new Set(); }
 async dispatch(attemptId) {
  const a=this.work.attempt(attemptId);
  if(!a||a.state!=='dispatching'||a.turn_id||this.sending.has(attemptId))return {submitted:false};
  const unit=this.work.unit(a.unit_id);
  if(unit?.state!=='dispatching')return {submitted:false};
  // Same-process concurrent joins cannot issue two requests. After process death,
  // recoverDispatches makes the persisted attempt unknown; reconcile, never resend.
  // Persist the send boundary before touching transport. This CAS also fences
  // separate adapter processes; a stable client ID alone is not server dedup.
  if(!this.work.uncertain(a.id,"Native submission begun; acceptance not yet settled"))return {submitted:false};
  this.sending.add(attemptId);
  try {
   const result=await this.runtime.call('turn/start',{threadId:a.thread_id,
    clientUserMessageId:a.id,input:[{type:'text',text:unit.instruction}]});
   const turnId=result?.turn?.id;
   if(typeof turnId!=='string'||!turnId){this.work.uncertain(a.id,'Native acknowledgment has no turn identity');return {submitted:null,state:'unknown'};}
   if(!this.work.accepted(a.id,turnId))return {submitted:null,state:'unknown'};
   if(this.work.attempt(a.id).state==='stopping')await this.interrupt(a.id);
   return {submitted:true,turnId,state:this.work.attempt(a.id).state};
  } catch(error) {
   this.work.uncertain(a.id,'Native submission or acknowledgment was not settled');
   return {submitted:null,state:this.work.attempt(a.id)?.state||'unknown'};
  } finally { this.sending.delete(attemptId); }
 }
 async reconcile(attemptId,{thread,complete}) {
  const a=this.work.attempt(attemptId);
  if(!a||thread?.id!==a.thread_id||complete!==true)return {matched:false};
  const matches=(thread.turns||[]).filter(t=>(t.items||[]).some(i=>
   i.type==='userMessage'&&(i.clientId===a.id||i.clientUserMessageId===a.id)));
  // Missing history or absence of the identity never proves non-delivery.
  if(matches.length!==1||!matches[0].id)return {matched:false,reason:matches.length>1?'Conflicting native acceptances':'No exact native acceptance'};
  if(!this.work.accepted(a.id,matches[0].id))return {matched:false};
  if(this.work.attempt(a.id).state==='stopping')await this.interrupt(a.id);
  return {matched:true,turnId:matches[0].id};
 }
 async interrupt(attemptId) {
  const a=this.work.attempt(attemptId);
  if(a?.state!=='stopping'||!a.turn_id)return {requested:false};
  // ACK is not terminal settlement and must not release the capacity slot.
  try {await this.runtime.call('turn/interrupt',{threadId:a.thread_id,turnId:a.turn_id});return {requested:true};}
  catch {return {requested:null};}
 }
}
