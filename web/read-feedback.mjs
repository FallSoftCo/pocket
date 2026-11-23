// UI observes an entire logical head lane, including coalesced follow-up reads.
// Fast background reconciliation never becomes a user-visible loading event.
export class ReadFeedback {
 constructor(render,{delay=400,minimum=500,settle=160,now=()=>performance.now(),schedule=(fn,ms)=>setTimeout(fn,ms),cancel=id=>clearTimeout(id)}={}){
  Object.assign(this,{render,delay,minimum,settle,now,schedule,cancel});this.entries=new Map();this.selected=null;this.visible=false;this.shownAt=0;
 }
 select(key){if(this.selected===key)return;this.selected=key;this.cancel(this.showTimer);this.cancel(this.hideTimer);this.showTimer=this.hideTimer=null;this.visible=false;this.render({visible:false,key});this.sync();}
 track(key,promise,{hasContent=false}={}){
  const previous=this.entries.get(key);if(previous?.promise===promise)return previous.observed;
  const entry={promise,hasContent,started:this.now()};this.entries.set(key,entry);
  entry.observed=promise.finally(()=>{if(this.entries.get(key)===entry){this.entries.delete(key);this.sync();}});this.sync();return entry.observed;
 }
 sync(){
  const entry=this.entries.get(this.selected);
  if(entry){
   this.cancel(this.hideTimer);this.hideTimer=null;
   if(this.visible)return;
   this.cancel(this.showTimer);
   const show=()=>{this.showTimer=null;if(this.entries.get(this.selected)!==entry)return;this.visible=true;this.shownAt=this.now();this.render({visible:true,key:this.selected,initial:!entry.hasContent});};
   const wait=entry.hasContent?Math.max(0,this.delay-(this.now()-entry.started)):0;
   if(wait)this.showTimer=this.schedule(show,wait);else show();
  }else{
   this.cancel(this.showTimer);this.showTimer=null;
   if(!this.visible||this.hideTimer!=null)return;
   this.hideTimer=this.schedule(()=>{this.hideTimer=null;if(this.entries.has(this.selected))return;this.visible=false;this.render({visible:false,key:this.selected});},Math.max(this.settle,this.minimum-(this.now()-this.shownAt)));
  }
 }
}
