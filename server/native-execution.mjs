import {createHash} from 'node:crypto';
const fail=(status,message)=>{throw Object.assign(Error(message),{status});};
const id=value=>typeof value==='string'&&value.length>0&&value.length<=512&&!/[\u0000-\u001f]/.test(value);
const unsupported=error=>error?.rpc?.code===-32601||error?.rpc?.code===-32602&&/unsupported|unknown (method|variant)/i.test(error.message);
export class NativeExecution {
 constructor(codex){this.codex=codex;this.outputBusy=false;}
 async call(method,params){await this.codex.connect();try{return await this.codex.call(method,params);}catch(error){if(unsupported(error))fail(409,'This Codex host does not support this execution control.');throw error;}}
 async terminals(threadId,cursor){
  if(!id(threadId)||cursor!==undefined&&(!id(cursor)||cursor.length>512))fail(400,'Invalid terminal page.');
  const result=await this.call('thread/backgroundTerminals/list',{threadId,limit:30,...(cursor?{cursor}:{})});
  if(!Array.isArray(result?.data)||result.data.length>30)fail(502,'Invalid native terminal response.');
  return {threadId,data:result.data,nextCursor:result.nextCursor??null};
 }
 async terminate(threadId,processId){
  if(!id(threadId)||!id(processId))fail(400,'Invalid terminal identity.');
  // Native thread-scoped ownership remains authoritative, including exit races.
  const result=await this.call('thread/backgroundTerminals/terminate',{threadId,processId});
  return {ok:true,threadId,processId,result};
 }
 async output(threadId,turnId,itemId,{offset=0,revision=null}={}){
  if(![threadId,turnId,itemId].every(id)||!Number.isSafeInteger(offset)||offset<0)fail(400,'Invalid command output position.');
  if(this.outputBusy)fail(429,'Another output read is in progress. Try again shortly.');
  this.outputBusy=true;
  try{
   const result=await this.call('thread/items/read',{threadId,turnId,itemIds:[itemId]});
   const entry=result?.data?.find(x=>x.turnId===turnId&&x.item?.id===itemId);
   if(!entry)fail(404,'This item has no persisted output yet. Refresh after it completes.');
   if(entry.item.type!=='commandExecution')fail(400,'This item is not a command.');
   const output=entry.item.aggregatedOutput;
   if(typeof output!=='string')fail(404,'Codex has not retained output for this command.');
   const hash=createHash('sha256').update(output).digest('hex');
   if(revision&&revision!==hash)fail(409,'Output changed. Read it again from the beginning.');
   if(offset>output.length)fail(400,'Output position is past the end.');
   // The native transport already caps a message at 100 MiB. Only one full-item
   // read is admitted at a time; no output is retained in our timeline/cache.
   // Client pages replace the prior page instead of accumulating transcript text.
   let end=Math.min(offset+24000,output.length);
   if(end<output.length&&/[\uD800-\uDBFF]/.test(output[end-1]))end--;
   return {threadId,turnId,itemId,revision:hash,text:output.slice(offset,end),offset,nextOffset:end<output.length?end:null,totalCharacters:output.length,status:entry.item.status,exitCode:entry.item.exitCode,source:'Codex retained command output'};
  }finally{this.outputBusy=false;}
 }
}
export function mountNativeExecution(app,{codex,route,requireId}){
 const execution=new NativeExecution(codex);
 app.get('/api/threads/:id/terminals',route(async(req,res)=>res.json(await execution.terminals(requireId(req.params.id),req.query.cursor))));
 app.post('/api/threads/:id/terminals/terminate',route(async(req,res)=>res.json(await execution.terminate(requireId(req.params.id),req.body.processId))));
 app.get('/api/threads/:id/command-output',route(async(req,res)=>res.json(await execution.output(requireId(req.params.id),req.query.turnId,req.query.itemId,{offset:Number(req.query.offset||0),revision:req.query.revision||null}))));
 return execution;
}
