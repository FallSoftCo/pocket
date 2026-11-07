// Owner-operated bulk cleanup. Read-only unless --apply is explicit. Uses the
// existing authenticated archive endpoint; never starts/resumes model work.
import {writeFileSync,readFileSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {ownerRequest} from './local-client.mjs';
import {inactiveArchiveReason} from '../server/session-archive.mjs';

export async function archiveInactive({request,cutoff,save,protectedIds=[],apply=false}){
  // Reading canonical archived pages reconciles the backend's cached membership,
  // including children archived outside NextComp. No archive RPC is replayed.
  const rows=new Map();let archivedObserved=0;
  for(const archived of [true,false]){
    const cursors=new Set();let cursor=null;
    do{
      const page=await request('/api/threads?view=coordinator&includeChildren=true&archived='+archived+(cursor?'&cursor='+encodeURIComponent(cursor):''));
      if(page.refreshPending)throw Error('Catalog is still refreshing; no archive sweep started');
      for(const t of page.threads||[])if(archived)archivedObserved++;else rows.set(t.id,t);
      cursor=page.nextCursor||null;
      if(cursor&&cursors.has(cursor))throw Error('Catalog cursor repeated; no archive sweep started');
      if(cursor)cursors.add(cursor);
    }while(cursor);
  }
  const protectedSet=new Set(protectedIds);
  for(const t of rows.values())if(inactiveArchiveReason(t,cutoff)||protectedSet.has(t.id)){
    let parent=t.parentThreadId;const seen=new Set();
    while(parent&&!seen.has(parent)){seen.add(parent);protectedSet.add(parent);parent=rows.get(parent)?.parentThreadId;}
  }
  const receipt={version:1,cutoff,observedAt:Date.now(),apply,inventory:rows.size,archivedObserved,results:[]};save(receipt);
  for(const t of rows.values()){
    const reason=protectedSet.has(t.id)?'Protected draft, selected session or descendant':inactiveArchiveReason(t,cutoff);
    const row={id:t.id,title:t.name||'',state:reason?'skipped':apply?'intent':'eligible',...(reason?{reason}:{})};receipt.results.push(row);save(receipt);
    if(reason||!apply)continue;
    try{const result=await request('/api/threads/'+encodeURIComponent(t.id)+'/archive',{method:'POST',body:{inactiveBefore:cutoff}});if(result.ok!==true)throw Error('Archive was not confirmed');row.state='archived';}
    catch(error){row.state=error.status===409?'skipped':'unknown';row.reason=error.message;save(receipt);if(row.state==='unknown'){receipt.state='needs-reconciliation';save(receipt);return receipt;}}
    save(receipt);
  }
  receipt.state='complete';receipt.finishedAt=Date.now();save(receipt);return receipt;
}
if(process.argv[1]&&resolve(process.argv[1])===resolve(new URL(import.meta.url).pathname)){
  const apply=process.argv.includes('--apply'),output=process.env.ARCHIVE_RECEIPT;
  if(!output||existsSync(output))throw Error('Set ARCHIVE_RECEIPT to a new private file; reconcile an existing receipt rather than repeating it');
  const cutoff=Date.now()-30*86400000;
  const protectedIds=process.env.ARCHIVE_PROTECTED_FILE?JSON.parse(readFileSync(process.env.ARCHIVE_PROTECTED_FILE,'utf8')):[];
  const result=await archiveInactive({request:ownerRequest,cutoff,protectedIds,apply,save:value=>writeFileSync(output,JSON.stringify(value,null,2)+'\n',{mode:0o600})});
  console.log(JSON.stringify({state:result.state,inventory:result.inventory,counts:result.results.reduce((all,row)=>(all[row.state]=(all[row.state]||0)+1,all),{})}));
}
