/** Explicit user input may continue a terminal failure; queued work stays paused.
 * Read the current native head, never a paginated/cached transcript. Native
 * turn/start remains the final arbiter if another client starts a turn meanwhile.
 */
export async function confirmedFailedTurn(codex,thread,validate){
 if(thread?.status?.type!=='systemError')return false;
 const transport=codex.transport;
 const page=await codex.call('thread/turns/list',{threadId:thread.id,limit:1,sortDirection:'desc',itemsView:'summary'});
 if(page?.data?.length!==1||!page.data[0].id||page.data[0].status!=='failed')return false;
 const fresh=(await codex.call('thread/read',{threadId:thread.id,includeTurns:false})).thread;
 if(codex.transport!==transport||fresh?.id!==thread.id)return false;
 validate(fresh);
 return ['idle','systemError'].includes(fresh.status?.type);
}
