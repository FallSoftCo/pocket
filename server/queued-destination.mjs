/** Queue admission uses current runtime metadata, never transcript pagination. */
export async function queuedDestination(codex,attached,threadId,validate){
 await codex.connect();
 let thread=(await codex.call('thread/read',{threadId,includeTurns:false})).thread;
 if(thread?.id!==threadId)throw Error('Queued destination belongs to another session.');
 validate(thread);
 if(thread.status?.type!=='active'&&!attached.has(threadId)){
  thread=(await codex.call('thread/resume',{threadId,excludeTurns:true})).thread;
  if(thread?.id!==threadId)throw Error('Queued destination belongs to another session.');
  validate(thread);attached.add(threadId);
 }
 return thread;
}
