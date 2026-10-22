import {itemRow} from './timeline.mjs';
// Observation compatibility is separate from display chronology. Never merge
// positions from different protocols or use an evidence page as a UI timeline.
const unsupported=e=>e?.rpc?.code===-32601&&/thread\/timeline\/list.*not supported/i.test(e.message);
export class CoordinatorHeadReader {
 constructor({codex,native,clock=Date.now}){Object.assign(this,{codex,native,clock});this.compatibility=new Map();this.reading=new Map();}
 read(threadId,{cursor=null,limit=64,observationOnly=false}={}){
  if(!observationOnly||cursor!==null)return this.native.read(threadId,{cursor,limit}).then(page=>({page}));
  if(!this.reading.has(threadId)){
   const job=this.observe(threadId,limit);this.reading.set(threadId,job);
   job.finally(()=>{if(this.reading.get(threadId)===job)this.reading.delete(threadId)}).catch(()=>{});
  }
  return this.reading.get(threadId).then(value=>structuredClone(value));
 }
 async observe(threadId,limit){
  if((this.compatibility.get(threadId)||0)<=this.clock()){
   try{return {page:await this.native.read(threadId,{limit})};}
   catch(error){
    if(!unsupported(error))throw error;
    this.compatibility.delete(threadId);this.compatibility.set(threadId,this.clock()+60000);
    while(this.compatibility.size>1024)this.compatibility.delete(this.compatibility.keys().next().value);
   }
  }
  const result=await this.codex.call('thread/turns/list',{threadId,limit:3,sortDirection:'desc',itemsView:'summary'});
  if(!Array.isArray(result?.data)||result.data.length>3||!(result.nextCursor===null||typeof result.nextCursor==='string'))
   throw Error('Invalid native observation page.');
  const seen=new Set(),rows=[];
  for(const turn of [...result.data].reverse()){
   if(typeof turn?.id!=='string'||seen.has(turn.id)||!Array.isArray(turn.items)||!['completed','inProgress','interrupted','failed'].includes(turn.status))throw Error('Invalid native observation turn.');
   seen.add(turn.id);
   rows.push({id:turn.id+'/header',kind:'turn',turnId:turn.id,status:'inProgress',startedAt:turn.startedAt});
   for(const item of turn.items){
    if(!['userMessage','agentMessage'].includes(item?.type))continue;
    const row=itemRow(item,turn.id,0);
    if(row)rows.push({...row,id:turn.id+'/'+item.id,itemId:item.id,...(turn.status==='inProgress'?{status:'inProgress'}:{})});
   }
   if(turn.status!=='inProgress')rows.push({id:turn.id+'/end',kind:'turnEnd',turnId:turn.id,status:turn.status,text:turn.error?.message||'',at:turn.completedAt});
  }
  return {observation:{rows,hasEarlier:result.nextCursor!==null,evidenceOnly:true,source:'native-turn-summary',turnLimit:3}};
 }
}
