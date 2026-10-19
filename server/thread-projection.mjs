/** Read-only history projection. Runtime attachment belongs to explicit control paths. */
export async function readThreadProjection(threadId,{codex,history,timeline,historyHeads,discovery},{before=null}={}){
 await codex.connect();
 const {thread:metadata}=await codex.call('thread/read',{threadId,includeTurns:false});
 if(metadata?.id!==threadId)throw Error('History belongs to another session.');
 discovery?.remember(metadata);
 const snapshotRevision=timeline.version,snapshotHistoryHead=historyHeads.snapshot(threadId);
 const thread=await history.read(metadata,{before,preferPaging:true,maxItems:8,maxBytes:512000,maxTurns:1});
 if(!before)timeline.seed(thread,{throughVersion:snapshotRevision});
 return {...thread,_pocketSnapshotRevision:snapshotRevision,_pocketSnapshotHistoryHead:snapshotHistoryHead};
}
