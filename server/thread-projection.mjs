/** Read-only history projection. Runtime attachment belongs to explicit control paths. */
export async function readThreadProjection(threadId,{codex,history,timeline,historyHeads,discovery},{before=null}={}){
 await codex.connect();
 const {thread:metadata}=await codex.call('thread/read',{threadId,includeTurns:false});
 if(metadata?.id!==threadId)throw Error('History belongs to another session.');
 discovery?.remember(metadata);
 const snapshotRevision=timeline.version,snapshotHistoryHead=historyHeads.snapshot(threadId);
 // Source pages, not just visible rows, bound work: raw/internal items may be filtered out.
 const window=before?{maxItems:8,maxTurns:1,maxSourcePages:2}:{maxItems:32,maxTurns:2,maxSourcePages:4,stopAfterConversation:true};
 const thread=await history.read(metadata,{before,preferPaging:true,maxBytes:512000,...window});
 if(!before)timeline.seed(thread,{throughVersion:snapshotRevision});
 return {...thread,_pocketSnapshotRevision:snapshotRevision,_pocketSnapshotHistoryHead:snapshotHistoryHead};
}
