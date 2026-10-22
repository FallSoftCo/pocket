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
    const page=await this.codex.call('thread/timeline/list',{threadId,limit,...(cursor!==null?{cursor}:{})});
    return validateNativeTimelinePage(page,{cursor,limit});
   })();
   this.reads.set(key,request);
   request.finally(()=>{if(this.reads.get(key)===request)this.reads.delete(key);}).catch(()=>{});
  }
  // Consumers receive a private payload; one view cannot mutate another's page.
  return structuredClone(await request);
 }
}
