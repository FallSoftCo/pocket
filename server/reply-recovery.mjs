export function recoverReply(db,threadId,id,{action,text,confirmUnknown=false},updatedAt){
  const row=db.prepare('SELECT * FROM outgoing WHERE id=? AND thread_id=?').get(id,threadId);
  const fail=(status,error)=>({status,error});
  if(!row)return fail(404,'Reply not found.');
  if(!['queued','held','failed','unknown'].includes(row.state))return fail(409,'This message cannot be changed while sending or after acceptance. Refresh the conversation.');
  if(!['edit','remove','send','retry'].includes(action))return fail(400,'Choose edit, remove, send, or retry.');
  if(action==='retry'&&!['failed','unknown'].includes(row.state))return fail(409,'This message is already waiting to send.');
  if(['retry','send'].includes(action)&&row.state==='unknown'&&confirmUnknown!==true)return fail(409,'Delivery is uncertain. Check the conversation before explicitly confirming a retry; it could send a duplicate.');
  const nextText=action==='edit'?String(text||'').trim():row.text;
  if(action!=='remove'&&(!nextText||nextText.length>32000))return fail(400,'Reply must be 1–32000 characters.');
  const dispatch=['retry','send'].includes(action);
  const state=action==='remove'?'cancelled':dispatch?'queued':row.state;
  const mode=action==='send'?'steer':row.mode;
  const changed=db.prepare('UPDATE outgoing SET text=?,mode=?,state=?,result=?,updated_at=? WHERE id=? AND thread_id=? AND state=?').run(nextText,mode,state,dispatch?null:row.result,updatedAt,id,threadId,row.state);
  if(!changed.changes)return fail(409,'Message state changed. Refresh before trying again.');
  return {status:200,id,state,dispatch};
}
