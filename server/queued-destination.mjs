/** Queue admission uses current runtime metadata, never transcript pagination. */
import {codexConnectionError} from './connection-errors.mjs';

/** Direct input needs a current turn identity, not the contents of the transcript.
 * Keep subscription ownership on the control connection; read only one native
 * turn header on the independent read lane. Codex still arbitrates the write.
 */
export async function replyDestination(codex,reader,attached,threadId,validate){
 await codex.connect();const transport=codex.transport;
 const checked=thread=>{if(thread?.id!==threadId)throw Error('Reply destination belongs to another session.');validate(thread);return thread;};
 let thread=checked((await reader.call('thread/read',{threadId,includeTurns:false})).thread);
 if(!attached.has(threadId)){
  thread=checked((await codex.call('thread/resume',{threadId,excludeTurns:true})).thread);
  attached.add(threadId);
 }
 let turns=[];
 if(thread.status?.type==='active'){
  const head=await reader.call('thread/turns/list',{threadId,limit:1,sortDirection:'desc',itemsView:'notLoaded'});
  const turn=head?.data?.[0];
  if(turn?.id&&turn.status==='inProgress')turns=[{id:turn.id,status:turn.status,items:[]}];
  else thread=checked((await reader.call('thread/read',{threadId,includeTurns:false})).thread);
 }
 if(!codex.ready||codex.transport!==transport)throw codexConnectionError('CODEX_DISCONNECTED');
 return {...thread,turns};
}

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
