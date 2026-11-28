const BALANCE_URL='https://api.deepseek.com/user/balance';
const BALANCE=/^(?:-?(?:0|[1-9][0-9]{0,23})(?:\.[0-9]{1,12})?)$/;
const CURRENCY=/^[A-Z]{3,8}$/;
const providerId='deepseek';

export class ProviderUsage {
 constructor({apiKey=process.env.DEEPSEEK_API_KEY,fetchImpl=globalThis.fetch,clock=Date.now,ttlMs=60000,timeoutMs=4000,onUpdate=null}={}){
  this.apiKey=typeof apiKey==='string'?apiKey.trim():'';this.fetchImpl=fetchImpl;this.clock=clock;this.ttlMs=ttlMs;this.timeoutMs=timeoutMs;this.onUpdate=onUpdate;this.inflight=null;
  this.current=this.apiKey?{providerId,configured:true,available:false,currency:null,totalBalance:null,updatedAt:0,stale:true,state:'loading'}:null;
 }
 snapshot(){return this.current?{...this.current}:null}
 async refresh({force=false}={}){
  if(!this.apiKey)return null;
  if(this.inflight)return this.inflight;
  if(!force&&this.current?.available&&this.clock()-this.current.updatedAt<this.ttlMs)return this.snapshot();
  const request=this.read().then(value=>{this.current=value;return this.publish();},()=>{const previous=this.current;this.current={providerId,configured:true,available:previous?.available===true,currency:previous?.currency||null,totalBalance:previous?.totalBalance||null,updatedAt:previous?.updatedAt||0,stale:true,state:previous?.available?'stale':'unavailable'};return this.publish();}).finally(()=>{if(this.inflight===request)this.inflight=null;});
  this.inflight=request;return request;
 }
 publish(){const value=this.snapshot();if(this.onUpdate)try{this.onUpdate(value);}catch{}return value;}
 async read(){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),this.timeoutMs);
  try{
   const response=await this.fetchImpl(BALANCE_URL,{method:'GET',headers:{Authorization:`Bearer ${this.apiKey}`,Accept:'application/json'},redirect:'error',signal:controller.signal});
   if(!response?.ok)throw new Error('DeepSeek balance unavailable');
   const data=await response.json(),balances=Array.isArray(data?.balance_infos)?data.balance_infos:[];
   const balance=balances.find(item=>item&&item.currency==='USD')||balances[0];
   const currency=typeof balance?.currency==='string'&&CURRENCY.test(balance.currency)?balance.currency:null;
   const totalBalance=typeof balance?.total_balance==='string'&&BALANCE.test(balance.total_balance)?balance.total_balance:null;
   if(!currency||totalBalance===null)throw new Error('DeepSeek balance unavailable');
   return {providerId,configured:true,available:true,currency,totalBalance,updatedAt:this.clock(),stale:false,state:'available'};
  }finally{clearTimeout(timer);}
 }
}
