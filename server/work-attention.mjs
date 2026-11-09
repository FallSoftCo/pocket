import {createHash} from 'node:crypto';
import {sessionWorkTime} from './session-catalog.mjs';

/** Read-only reasons for opening original work; never a notification feed or retry command.
 * At most one representative per kind (six total). Empty unverified results are
 * not an all-clear. Durable delivery receipts remain useful during native outages.
 */
export function workAttention({db,pending,codex,threads,refreshPending=false}) {
  const ids=[...new Set(threads.map(t=>t.id))],result=new Map();
  if(!ids.length)return result;
  const selected=JSON.stringify(ids),healthy=codex.ready===true;
  for(const t of threads)result.set(t.id,{version:1,items:[],verified:healthy&&!refreshPending&&!t.discoveryPending&&!['notLoaded','pending',undefined].includes(t.status?.type)});
  const add=(thread,kind,id,label)=>{const entry=result.get(thread);if(entry&&!entry.items.some(item=>item.kind===kind))entry.items.push({kind,id,label});};
  if(healthy)for(const request of pending.values()){
    const kind=/requestUserInput$/.test(request.method)?'question':/requestApproval$/.test(request.method)?'approval':null;
    if(!kind)continue;
    const identity=request._pocketUI?.token;
    // Connection-owned presentation tokens distinguish reused native request IDs.
    if(!identity)continue;
    add(request.params?.threadId,kind,'request:'+createHash('sha256').update(identity).digest('hex').slice(0,24),kind==='question'?'Answer question':'Review approval');
  }
  const rows=db.prepare(`SELECT id,thread_id,state FROM (
    SELECT id,thread_id,state,ROW_NUMBER() OVER (PARTITION BY thread_id,state ORDER BY created_at DESC,id) AS rank
    FROM outgoing WHERE thread_id IN (SELECT value FROM json_each(?)) AND state IN ('failed','unknown')
  ) WHERE rank=1`).all(selected);
  for(const row of rows)add(row.thread_id,row.state==='failed'?'deliveryFailed':'deliveryUnknown','outgoing:'+row.id,row.state==='failed'?'Review failed message':'Check message delivery');
  const contexts=new Map(db.prepare('SELECT thread_id,needs_input,at FROM pocket_catalog_context WHERE thread_id IN (SELECT value FROM json_each(?))').all(selected).map(row=>[row.thread_id,row]));
  for(const t of threads){
    if(!result.get(t.id).verified)continue;
    const context=contexts.get(t.id);
    // Same validity predicate as CatalogContext.get; no copied summary text.
    if(context?.needs_input&&t.status?.type==='idle'&&sessionWorkTime(t)<=context.at)add(t.id,'needsInput','context:'+context.at,'Input requested');
    if(t.status?.type==='systemError')add(t.id,'runtimeError','runtime:'+t.id+':systemError','Review runtime error');
  }
  return result;
}
