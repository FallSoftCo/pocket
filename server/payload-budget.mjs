import {statSync} from 'node:fs';
const MIB=1024*1024;
const integer=(value,fallback,{min=1,max=Number.MAX_SAFE_INTEGER}={})=>{
  const parsed=Number(value);
  return Number.isSafeInteger(parsed)&&parsed>=min&&parsed<=max?parsed:fallback;
};
const providerSet=value=>new Set(String(value||'deepseek').split(',').map(item=>item.trim()).filter(Boolean));

export function encodedImageBytes(bytes){return 4*Math.ceil(Number(bytes)/3);}

/** Count only images that can still be replayed from the current compacted
 * context. A missing boundary within the bounded scan is treated as unsafe:
 * continuing at that point is cheaper and safer than guessing at the payload.
 */
export class PayloadBudget {
  constructor({codex,env=process.env,stat=statSync,pageSize=100,maxPages=64,providers=null}={}){
    this.codex=codex;this.stat=stat;
    this.maxActiveImages=integer(env.NEXTCOMP_MAX_ACTIVE_IMAGES,12,{min:0});
    this.maxImageBytes=integer(env.NEXTCOMP_MAX_IMAGE_BYTES,24*MIB,{min:0});
    this.pageSize=integer(pageSize,100,{min:1,max:100});
    this.maxPages=integer(maxPages,64,{min:1,max:512});
    const configured=providers??env.NEXTCOMP_PAYLOAD_GUARD_PROVIDERS??'deepseek';
    this.providers=providerSet(configured);
    this.allProviders=this.providers.has('*');
  }
  applies(thread){return this.allProviders||this.providers.has(String(thread?.modelProvider||''));}
  scan(threadId){
    const state={threadId,count:0,diskBytes:0,estimatedEncodedBytes:0,missingImages:0,boundaryFound:false,truncated:false,boundaryItemId:null,pages:0,items:0};
    return this.walk(threadId,state);
  }
  async walk(threadId,state){
    let cursor=null;
    while(state.pages<this.maxPages){
      let page;
      try{page=await this.codex.call('thread/items/list',{threadId,limit:this.pageSize,sortDirection:'desc',...(cursor?{cursor}:{})});}
      catch(error){
        error.payloadBudgetScan=true;
        throw error;
      }
      state.pages++;
      for(const entry of page?.data||[]){
        const item=entry?.item;if(!item)continue;
        state.items++;
        if(item.type==='contextCompaction'){
          state.boundaryFound=true;state.boundaryItemId=item.id||null;
          return this.result(state);
        }
        if(item.type!=='imageView')continue;
        state.count++;
        let size;
        try{size=this.stat(item.path)?.size;}
        catch{size=null;}
        if(!Number.isFinite(size)||size<0){state.missingImages++;continue;}
        state.diskBytes+=size;
        state.estimatedEncodedBytes+=encodedImageBytes(size);
      }
      if(!page?.nextCursor)return this.result(state);
      cursor=page.nextCursor;
    }
    state.truncated=true;
    return this.result(state);
  }
  result(state){
    const countExceeded=state.count>this.maxActiveImages;
    const bytesExceeded=state.estimatedEncodedBytes>this.maxImageBytes;
    const noBoundary=state.truncated&&!state.boundaryFound;
    const unverifiable=state.missingImages>0;
    let reason=null;
    if(noBoundary)reason=`The active context exceeded ${this.maxPages*this.pageSize} scanned items before a compaction boundary could be verified.`;
    else if(unverifiable)reason=`${state.missingImages} active image${state.missingImages===1?'':'s'} could not be measured safely.`;
    else if(countExceeded||bytesExceeded)reason=`The active context contains ${state.count} inline images (${(state.estimatedEncodedBytes/MIB).toFixed(1)} MiB encoded; limits ${this.maxActiveImages} images / ${(this.maxImageBytes/MIB).toFixed(1)} MiB).`;
    return {...state,countExceeded,bytesExceeded,unverifiable,exceeded:!!(noBoundary||unverifiable||countExceeded||bytesExceeded),reason,
      limits:{activeImages:this.maxActiveImages,imageBytes:this.maxImageBytes}};
  }
}
