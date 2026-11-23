import {createHash} from 'node:crypto';
import {CoordinatorRoomIntents} from './coordinator-room-intents.mjs';
const digest=v=>createHash('sha256').update(JSON.stringify(v)).digest('hex');
const fail=m=>Object.assign(Error(m),{status:409});
// Exact shipped implementation whose one-call/two-POST collision is recovered.
export const COLLIDING_SOURCE_SHA='15721a397f694bf869950f7f87aaa7ba1928faacc86e4ba6058177907326f31a';
function original(db,s){return db.prepare('SELECT * FROM coordinator_room_intents WHERE device=? AND source=? AND binding=? AND id=?').get(s.device,s.source,s.binding,s.id);}
/** Positive local pre-dispatch proof, not an inference from a missing HTTP
 * receipt. Only the exact shipped legacy-key collision qualifies. */
export function prepareIntentCollisionRecovery(db,scope,sourceSha){
 if(sourceSha!==COLLIDING_SOURCE_SHA)throw fail('Reviewed original transport source required.');
 const intent=original(db,scope);if(!intent||intent.phase!=='sending'||intent.result!==null)throw fail('Original unresolved sending intent required.');
 const p=JSON.parse(intent.payload);
 if(!p.creation||p.roomId!==p.creation.id||p.message.operationId!==intent.id+'-message'||p.creation.operationId!==intent.id+'-room'||p.message.kind!=='request'||p.message.audience!=='room')throw fail('Exact original create-and-start payload required.');
 const calls=db.prepare('SELECT * FROM voice_calls WHERE device=?').all(scope.device).filter(c=>
  'coordinator-intent-'+digest([scope.device,scope.source,scope.binding,c.turn_id,c.call_id]).slice(0,48)===intent.id);
 if(calls.length!==1)throw fail('Unique original human tool-call provenance required.');
 const call=calls[0],turn=db.prepare('SELECT * FROM voice_turns WHERE device=? AND id=?').get(scope.device,call.turn_id);
 const result=JSON.parse(call.result||'null'),texts=result?.contentItems?.filter(x=>x.type==='inputText').map(x=>x.text)||[];
 if(call.state!=='done'||result?.success!==false||!texts.some(t=>{try{const e=JSON.parse(t);return e.commandId===intent.id&&e.code==='ROOM_ADMISSION_UNKNOWN'&&e.error.startsWith('Initial human instruction was not confirmed.');}catch{return false;}})||!turn||!db.prepare('SELECT 1 FROM devices WHERE id=?').get(scope.device))throw fail('Retained original human failure/device/turn required.');
 const id=digest([scope.device,scope.source,call.turn_id,call.call_id]);
 const remote=db.prepare('SELECT * FROM coordinator_remote_commands WHERE id=?').get(id);
 if(!remote||remote.state!=='responded'||remote.device_id!==scope.device||remote.source_id!==scope.source||remote.voice_turn!==call.turn_id||remote.path!=='/api/hollywood/v3/rooms'||remote.request_id!==p.roomId||remote.body_hash!==digest(p.creation))throw fail('Exact accepted creation occupying the legacy transport key required.');
 const created=JSON.parse(remote.result),room=created.room||created;
 if(room.id!==p.creation.id||room.name!==p.creation.name||room.purpose!==p.creation.purpose||room.owner!==p.creation.ownerId||created.binding?.bindingId!==scope.binding||!created.binding?.principalId||!created.binding?.authorityId)throw fail('Original canonical creation/human binding differs.');
 const path='/api/hollywood/v3/rooms/'+p.roomId+'/messages';
 if(db.prepare('SELECT 1 FROM coordinator_remote_commands WHERE device_id=? AND source_id=? AND (request_id=? OR (path=? AND body_hash=?))').get(scope.device,scope.source,p.message.operationId,path,digest(p.message)))throw fail('Message dispatch evidence exists: reconcile it, never retry.');
 // The loaded legacy function finds this different-path existing row and throws
 // BEFORE INSERT/fetch. Neither 403/404 nor empty history is used as proof.
 return {schema:'coordinator-room-intent-collision-recovery/v1',scope,sourceSha,decision:'initial_instruction_not_dispatched',
  intentDigest:digest(intent),creationTransportDigest:digest(remote),voiceCallDigest:digest(call),
  actor:{deviceId:scope.device,voiceTurnId:call.turn_id,voiceCallId:call.call_id,hollywoodBinding:scope.binding},
  identity:created.binding,roomId:p.roomId,messageOperationId:p.message.operationId,
  original:{intent,creationTransport:remote,voiceCall:call},noCreationReplay:true,noUnknownSendRetry:true};
}
export function applyIntentCollisionRecovery(db,plan,sourceSha){
 db.exec('BEGIN IMMEDIATE');
 try{
  const current=prepareIntentCollisionRecovery(db,plan.scope,sourceSha);
  if(digest(plan)!==digest(current))throw fail('Reviewed plan/original-row CAS changed.');
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_room_intent_recoveries(device TEXT NOT NULL,source TEXT NOT NULL,binding TEXT NOT NULL,id TEXT NOT NULL,plan_digest TEXT NOT NULL,plan TEXT NOT NULL,state TEXT NOT NULL,result TEXT,PRIMARY KEY(device,source,binding,id));`);
  const s=plan.scope,old=db.prepare('SELECT * FROM coordinator_room_intent_recoveries WHERE device=? AND source=? AND binding=? AND id=?').get(s.device,s.source,s.binding,s.id);
  if(old&&old.plan_digest!==digest(plan))throw fail('Original recovery already has a different reviewed plan.');
  if(!old)db.prepare("INSERT INTO coordinator_room_intent_recoveries VALUES(?,?,?,?,?,?,'prepared',NULL)").run(s.device,s.source,s.binding,s.id,digest(plan),JSON.stringify(plan));
  db.exec('COMMIT');return {prepared:!old,state:old?.state||'prepared',planDigest:digest(plan),originalRowsUnchanged:true};
 }catch(e){db.exec('ROLLBACK');throw e;}
}
/** Explicit owner continuation of the original authorized human instruction.
 * One durable claim, standard room/transport admission, no turn or worker wake.
 * A lost response remains unknown and this operation cannot dispatch again. */
export async function continueIntentCollisionRecovery(db,plan,{get,post}){
 const s=plan.scope,key=[s.device,s.source,s.binding,s.id],manager=new CoordinatorRoomIntents(db),scope=manager.scope(s.device,s.source,s.binding);
 let recovery=db.prepare('SELECT * FROM coordinator_room_intent_recoveries WHERE device=? AND source=? AND binding=? AND id=?').get(...key);
 if(!recovery||recovery.plan_digest!==digest(plan))throw fail('Applied reviewed recovery required.');
 if(recovery.state==='accepted')return manager.inspect(scope.get(s.id),scope,get,plan.identity);
 if(recovery.state!=='prepared')throw fail('Original recovery dispatch is unconfirmed; inspect only, never resend.');
 const identity=await get('/api/hollywood/v3/identity');
 if(['bindingId','principalId','authorityId'].some(k=>identity[k]!==plan.identity[k]))throw fail('Original human/environment identity changed.');
 const detail=await get('/api/hollywood/v3/rooms/'+plan.roomId);
 if(detail.room?.id!==plan.roomId||detail.permissions?.can_request!==true)throw fail('Original room is not currently writable by its original human.');
 db.exec('BEGIN IMMEDIATE');
 try{
  if(digest(original(db,s))!==plan.intentDigest)throw fail('Original intent changed before continuation.');
  const result=db.prepare("UPDATE coordinator_room_intent_recoveries SET state='dispatching' WHERE device=? AND source=? AND binding=? AND id=? AND state='prepared' AND plan_digest=?").run(...key,digest(plan));
  if(result.changes!==1)throw fail('Original recovery already claimed.');
  db.exec('COMMIT');
 }catch(e){db.exec('ROLLBACK');throw e;}
 try{
  // The reviewed overlay supplies the known pre-dispatch phase. No original
  // row is reset; normal admission advances it only to a verified receipt.
  const result=await manager.execute({...scope.get(s.id),phase:'created'},scope,{identity,get,post,action:()=>{}});
  db.prepare("UPDATE coordinator_room_intent_recoveries SET state='accepted',result=? WHERE device=? AND source=? AND binding=? AND id=?").run(JSON.stringify(result),...key);
  return result;
 }catch(e){
  db.prepare("UPDATE coordinator_room_intent_recoveries SET state='unknown',result=? WHERE device=? AND source=? AND binding=? AND id=?").run(JSON.stringify({error:e.message,status:e.status||null}),...key);throw e;
 }
}
