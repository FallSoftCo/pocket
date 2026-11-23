import {readThreadMetadata,threadReadError} from './thread-read-availability.mjs';
/** Read-only history projection. Runtime attachment belongs to explicit control paths. */
export async function readThreadProjection(threadId,{codex,history,timeline,historyHeads,discovery,known,emptyThread},{before=null}={}){
 const archiveRevision=discovery?.archiveRevision;
 const emptyProjection=metadata=>({...metadata,status:{type:'idle'},statusSource:'persisted-empty',turns:[],_pocketPage:{hasEarlier:false,before:null},_pocketSnapshotRevision:timeline.version,_pocketSnapshotHistoryHead:historyHeads.snapshot(threadId)});
 await codex.connect();
 let metadata;
 try{metadata=await readThreadMetadata(codex,threadId,{known});}
 catch(error){
  const saved=known?.(threadId);
  if(saved?.id===threadId&&error.readAvailability&&emptyThread?.(saved))return emptyProjection(saved);
  throw error;
 }
 // A concurrent archive/restore is newer lifecycle evidence than this read.
 // Keep the history readable without letting stale metadata undo that action.
 if(discovery?.archiveRevision===archiveRevision)discovery?.remember(metadata);
 const snapshotRevision=timeline.version,snapshotHistoryHead=historyHeads.snapshot(threadId);
 // Source pages, not just visible rows, bound work: raw/internal items may be filtered out.
 const window=before?{maxItems:8,maxTurns:1,maxSourcePages:2}:{maxItems:32,maxTurns:2,maxSourcePages:4,stopAfterConversation:true};
 let thread;
 try{thread=await history.read(metadata,{before,preferPaging:true,maxBytes:512000,...window});}
 catch(error){
  const projected=threadReadError(error,{threadId,metadata,operation:'history/read'});
  const empty=projected.readAvailability&&emptyThread?.(metadata);
  if(empty)return emptyProjection(metadata);
  throw projected;
 }
 if(!before)timeline.seed(thread,{throughVersion:snapshotRevision});
 return {...thread,_pocketSnapshotRevision:snapshotRevision,_pocketSnapshotHistoryHead:snapshotHistoryHead};
}
