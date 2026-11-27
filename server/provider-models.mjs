import {createHash} from 'node:crypto';
const modelId=/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}$/;
const unavailable=providerId=>({providerId,state:'unavailable',models:[],notice:'Model list unavailable. Retry or enter a model ID manually.'});

// On-demand metadata only. Never use Codex's OpenAI catalog as an external
// provider's inventory, and never start inference to discover model names.
export class ProviderModels {
 constructor({fetchImpl=globalThis.fetch,env=process.env,clock=Date.now,timeoutMs=4000}={}){Object.assign(this,{fetchImpl,env,clock,timeoutMs});this.cache=new Map();this.pending=new Map();}
 async read(provider,config){
  const id=provider.id,settings=config?.model_providers?.[id];
  let url;
  try{
   url=new URL(provider.baseUrl);
   if(url.username||url.password||url.search||url.hash||!(url.protocol==='https:'||url.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(url.hostname)))return unavailable(id);
   if(!settings||new URL(settings.base_url).href.replace(/\/$/,'')!==url.href.replace(/\/$/,''))return unavailable(id);
  }catch{return unavailable(id);}
  url.pathname=url.pathname.replace(/\/$/,'')+'/models';
  const token=settings.experimental_bearer_token||(settings.env_key?this.env[settings.env_key]:null);
  if(token!=null&&(typeof token!=='string'||/[\r\n]/.test(token)))return unavailable(id);
  const key=createHash('sha256').update(JSON.stringify([id,url.href,token||''])).digest('hex');
  const cached=this.cache.get(key);if(cached&&cached.until>this.clock())return cached.value;
  if(this.pending.has(key))return this.pending.get(key);
  const request=this.load(id,url,token,config).catch(()=>unavailable(id)).then(value=>{
   this.cache.set(key,{value,until:this.clock()+(value.state==='available'?60000:10000)});
   while(this.cache.size>32)this.cache.delete(this.cache.keys().next().value);
   return value;
  }).finally(()=>this.pending.delete(key));
  this.pending.set(key,request);return request;
 }
 async load(providerId,url,token,config){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),this.timeoutMs);
  try{
   const response=await this.fetchImpl(url,{method:'GET',headers:{Accept:'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},redirect:'error',signal:controller.signal});
   if(!response.ok)throw Error('Unavailable');
   const reader=response.body.getReader(),chunks=[];let size=0;
   try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>256*1024)throw Error('Catalog too large');chunks.push(Buffer.from(value));}}finally{await reader.cancel().catch(()=>{});}
   const data=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!Array.isArray(data.data)||data.data.length>256)throw Error('Invalid catalog');
   const models=[],seen=new Set();for(const item of data.data){if(!modelId.test(item?.id||'')||seen.has(item.id))continue;seen.add(item.id);models.push({id:item.id,name:typeof item.name==='string'&&item.name.trim()?item.name.trim().slice(0,200):item.id});}
   if(!models.length)throw Error('No model IDs');
   const defaultModel=config.model_provider===providerId&&seen.has(config.model)?config.model:models[0].id;
   return {providerId,state:'available',models,defaultModel,observedAt:this.clock(),notice:'Models advertised by this provider. Loading this list does not run a model.'};
  }finally{clearTimeout(timer);}
 }
}
