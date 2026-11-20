// Reconnection restores live observation, not historical runtime admission.
// Disabled, idle and unloaded watches keep their saved state without resuming.
export async function restoreWatchedSubscriptions({watches,codex,subscriptions,attached,onObserved=()=>{},onSubscribed=()=>{}}){
 const loaded=await subscriptions.inventory();
 const candidates=watches.filter(w=>w.enabled===1&&loaded.has(w.thread_id)&&!attached.has(w.thread_id));
 let next=0;
 await Promise.all(Array.from({length:Math.min(2,candidates.length)},async()=>{
  while(next<candidates.length){
   const id=candidates[next++].thread_id;
   try{
    const {thread}=await codex.call('thread/read',{threadId:id,includeTurns:false});
    if(thread?.id!==id||!['active','idle'].includes(thread.status?.type))continue;
    onObserved(thread);
    if(thread.status.type!=='active')continue;
    if(await subscriptions.observe(thread)){
     attached.add(id);onSubscribed(thread);
    }
   }catch{if(!codex.ready)break;}
  }
 }));
}
