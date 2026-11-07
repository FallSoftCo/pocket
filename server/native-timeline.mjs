import {itemRow,displayTurnError} from './timeline.mjs';
// Codex owns chronology. This adapter neither constructs turns nor infers order.
// No attachment, thread resume, command journal or model activation occurs here.
const ranks=new Map([['turnStarted',0],['item',1],['realtime',2],['turnCompleted',3]]);
const invalid=message=>Object.assign(new Error(message),{code:'INVALID_NATIVE_TIMELINE',status:502});
const validId=value=>typeof value==='string'&&value.length>0&&value.length<=512;
export function nativeTimelineKey(entry){
 if(!entry||!Number.isSafeInteger(entry.position)||entry.position<0||!ranks.has(entry.type))
  throw invalid('Codex returned an invalid timeline position.');
 const id=entry.type==='item'||entry.type==='realtime'?entry.item?.id:entry.turnId;
 if(!validId(id)||(entry.type!=='realtime'&&!validId(entry.turnId)))
  throw invalid('Codex returned an invalid timeline identity.');
 return [entry.position,ranks.get(entry.type),id];
}
export function compareNativeTimelineKeys(left,right){
 return left[0]-right[0]||left[1]-right[1]||(left[2]<right[2]?-1:left[2]>right[2]?1:0);
}
export function validateNativeTimelinePage(page,{cursor=null,limit=64}={}){
 if(!page||!Array.isArray(page.data)||page.data.length>limit||
   !(page.nextCursor===null||typeof page.nextCursor==='string'&&page.nextCursor.length>0&&page.nextCursor.length<=16384)||
   !(page.activeRealtimeSessionAtPageStart===null||validId(page.activeRealtimeSessionAtPageStart)))
  throw invalid('Codex returned an invalid timeline page.');
 if(cursor!==null&&page.nextCursor===cursor)throw invalid('Codex returned a repeated timeline cursor.');
 let previous=null;
 for(const entry of page.data){
  const key=nativeTimelineKey(entry);
  if(previous&&compareNativeTimelineKeys(previous,key)>=0)
   throw invalid('Codex returned a timeline page outside canonical order.');
  previous=key;
 }
 // Preserve the native entries, positions and opaque cursor byte-for-byte.
 return page;
}
export class NativeTimelineReader {
 constructor(codex){this.codex=codex;this.reads=new Map();}
 async read(threadId,{cursor=null,limit=64}={}){
  if(!validId(threadId)||!Number.isInteger(limit)||limit<1||limit>100||
    !(cursor===null||typeof cursor==='string'&&cursor.length>0&&cursor.length<=16384))
   throw Object.assign(new Error('Invalid native timeline request.'),{status:400});
  const key=JSON.stringify([threadId,cursor,limit]);
  let request=this.reads.get(key);
  if(!request){
   request=(async()=>{
    await this.codex.connect();
    let page;
    try{page=await this.codex.call('thread/timeline/list',{threadId,limit,...(cursor!==null?{cursor}:{})});}
    catch(error){
     if(error?.rpc?.code===-32601&&/thread\/timeline\/list.*not supported/i.test(error.message||''))
      throw Object.assign(new Error('This session uses turn history rather than native timeline history.'),{status:409,code:'NATIVE_TIMELINE_UNSUPPORTED',rpc:error.rpc});
     throw error;
    }
    return validateNativeTimelinePage(page,{cursor,limit});
   })();
   this.reads.set(key,request);
   request.finally(()=>{if(this.reads.get(key)===request)this.reads.delete(key);}).catch(()=>{});
  }
  // Consumers receive a private payload; one view cannot mutate another's page.
  return structuredClone(await request);
 }
}

/** Display projection only. Every source entry keeps its canonical key even if
 * its public presentation is empty. Raw reasoning/audio/provider payloads never
 * become phone transcript rows. */
export function projectNativeTimeline(page,{identity=null,threadId,revision=0}={}){
 const entries=page.data.map(entry=>{
  const key=nativeTimelineKey(entry),turnId=entry.turnId||'realtime:'+entry.item.realtimeSessionId;
  let row=null;
  if(entry.type==='item'){
   let item=entry.item;
   if(identity)item=identity.snapshot({id:threadId,turns:[{id:turnId,items:[item]}]}).turns[0].items[0];
   row=itemRow(item,turnId,revision);
   if(row)row={...row,id:turnId+'/'+entry.item.id,itemId:entry.item.id};
  }else if(entry.type==='turnStarted')row={id:turnId+'/header',turnId,kind:'turn',status:'inProgress',startedAt:entry.startedAt,version:revision};
  else if(entry.type==='turnCompleted')row={id:turnId+'/end',turnId,kind:'turnEnd',status:entry.status,
   durationMs:entry.durationMs,text:entry.error?.message||'',error:displayTurnError(entry.error),version:revision};
  else if(entry.item.type==='transcriptSegment')row={id:'realtime/'+entry.item.id,itemId:entry.item.id,turnId,
   kind:entry.item.role==='user'?'user':'message',type:'realtimeTranscript',text:entry.item.text||'',version:revision};
  if(row)row={...row,_nativePosition:key[0],_nativeRank:key[1],_nativeId:key[2],
   ...(row.kind==='user'?{_inputOrder:'canonical'}:{})};
  return {id:entry.type+':'+turnId+':'+key[2],key,row};
 });
 return {native:1,entries,rows:entries.flatMap(entry=>entry.row?[entry.row]:[]),
  hasEarlier:page.nextCursor!==null,before:page.nextCursor,
  activeRealtimeSessionAtPageStart:page.activeRealtimeSessionAtPageStart};
}
