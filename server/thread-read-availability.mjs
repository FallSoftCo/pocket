// Read availability is not session lifecycle authority. Never resume, archive,
// delete cached history, or change delivery receipts in response to these errors.
export const isThreadNotLoaded=(error,threadId)=>error?.rpc?.code===-32600&&error.rpc.message===`thread not loaded: ${threadId}`;

export function cachedThreadMetadata(db,threadId){
 try{
  const row=db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(threadId);
  const metadata=row?JSON.parse(row.metadata):null;
  return metadata?.id===threadId?metadata:null;
 }catch{return null;} // Missing/malformed evidence cannot establish ephemerality.
}

export function threadReadError(error,{threadId,metadata=null,operation}){
 let code,message,kind;
 if(operation==='history/read'&&error?.rpcMethod==='thread/turns/list'&&error?.rpc?.code===-32600&&
    error.rpc.message===`thread ${threadId} is not materialized yet; thread/turns/list is unavailable before first user message`){
  code='THREAD_NOT_MATERIALIZED';kind='notMaterialized';
  message='Codex has not stored a first message for this session yet. Previously saved messages are retained.';
 }else if(isThreadNotLoaded(error,threadId)){
  if(metadata?.id===threadId&&metadata.ephemeral===true){
   code='THREAD_EPHEMERAL_UNAVAILABLE';kind='ephemeralUnavailable';
   message='This temporary session is no longer loaded in Codex, and Codex cannot provide its history for this read. Previously saved messages are retained.';
  }else{
   code='THREAD_HISTORY_UNAVAILABLE';kind='historyUnavailable';
   message='Codex cannot currently find this session in its loaded or stored history. Saved messages are retained. This does not establish that the history is permanently lost; check the original environment.';
  }
 }else if(error?.rpc?.code===-32600&&error.rpc.message===`thread ${threadId} is archived`){
  code='THREAD_ARCHIVED';kind='archived';
  message='This session is archived. Native timeline reading is unavailable; its stored history may still be readable. Reading does not unarchive or resume it.';
 }else return error;
 return Object.assign(new Error(message,{cause:error}),{status:409,code,rpc:error.rpc,readAvailability:{threadId,kind,operation}});
}

export async function readThreadMetadata(codex,threadId,{known=()=>null}={}){
 let result;
 try{result=await codex.call('thread/read',{threadId,includeTurns:false});}
 catch(error){throw threadReadError(error,{threadId,metadata:known(threadId),operation:'thread/read'});}
 if(result?.thread?.id!==threadId)throw Object.assign(Error('History belongs to another session.'),{status:502});
 return result.thread;
}
