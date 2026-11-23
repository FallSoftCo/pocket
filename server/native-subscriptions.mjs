/** Live-event lifecycle, separate from read-only timeline pagination. Never
 * loads historical inventories or sends turns/configuration overrides. Only an
 * observed active, already-loaded runtime is eligible for native subscription. */
export class NativeSubscriptions {
 constructor(codex,{limit=64,concurrency=2}={}){
  this.codex=codex;this.limit=limit;this.concurrency=concurrency;this.generation=0;
  this.subscribed=new Set();this.pending=new Map();this.queue=[];this.active=0;this.loadedRead=null;this.loadedAt=0;this.loaded=null;
  codex.on('disconnected',()=>this.reset());
 }
 reset(){this.generation++;this.subscribed.clear();for(const intent of this.pending.values())intent.resolve(false);this.pending.clear();this.queue=[];this.loadedRead=null;this.loaded=null;this.loadedAt=0;}
 async inventory(){
  if(this.loaded&&Date.now()-this.loadedAt<1000)return this.loaded;
  if(!this.loadedRead){
   const generation=this.generation;
   const request=this.codex.call('thread/loaded/list',{}).then(value=>{
    if(!Array.isArray(value.data)||value.data.some(id=>typeof id!=='string'))throw Error('Invalid native loaded inventory.');
    const loaded=new Set(value.data);
    if(generation===this.generation){this.loaded=loaded;this.loadedAt=Date.now();}
    return loaded;
   });
   this.loadedRead=request;
   request.finally(()=>{if(this.loadedRead===request)this.loadedRead=null;}).catch(()=>{});
  }
  return this.loadedRead;
 }
 observe(thread,{alreadyAttached=false}={}){
  if(thread?.status?.type!=='active'||typeof thread.id!=='string'||!this.codex.ready)return Promise.resolve(false);
  const id=thread.id;
  if(alreadyAttached||this.subscribed.has(id)){this.subscribed.add(id);return Promise.resolve(true);}
  if(this.pending.has(id))return this.pending.get(id).promise;
  if(this.pending.size>=this.limit)return Promise.resolve(false);
  let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});
  this.pending.set(id,{promise,resolve,reject,generation:this.generation});this.queue.push(id);this.pump();return promise;
 }
 pump(){
  while(this.active<this.concurrency&&this.queue.length){
   const id=this.queue.shift(),intent=this.pending.get(id);if(!intent)continue;
   this.active++;
   (async()=>{
    if(!this.codex.ready||intent.generation!==this.generation)return false;
    if(!(await this.inventory()).has(id)||!this.codex.ready||intent.generation!==this.generation)return false;
    // Native running-thread fast path reuses the existing runtime. There is no
    // turn/start, permission/model override, historical sweep or worker fork.
    const result=await this.codex.call('thread/resume',{threadId:id,excludeTurns:true});
    if(result.thread?.id!==id)throw Error('Native subscription returned another thread.');
    if(intent.generation!==this.generation)return false;
    this.subscribed.add(id);return true;
   })().then(intent.resolve,intent.reject).finally(()=>{
    if(this.pending.get(id)===intent)this.pending.delete(id);this.active--;this.pump();
   });
  }
 }
}
