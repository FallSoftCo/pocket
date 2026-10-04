// Coalesce token traffic before broadcasting. Timers are not reset by new tokens.
export class LiveActivityDelivery {
  constructor(emit,{clock=Date.now,schedule=setTimeout,cancel=clearTimeout}={}){
    Object.assign(this,{emit,clock,schedule,cancel});this.pending=new Map();this.seen=new Map();this.events=0;this.window=clock();
  }
  pressure(){const now=this.clock();if(now-this.window>2000){this.window=now;this.events=0;}
    for(const [id,at] of this.seen)if(now-at>2000)this.seen.delete(id);
    return Math.log1p(this.seen.size+this.events/2);
  }
  preview(id,value){this.pressure();this.events++;this.seen.set(id,this.clock());this.pending.set(id,value);
    if(this.previewTimer===undefined)this.previewTimer=this.schedule(()=>{this.previewTimer=undefined;for(const [threadId,preview] of this.pending)this.emit('sessionPreview',{threadId,...preview});this.pending.clear();},Math.min(800,100+this.pressure()*130));
  }
  activity(snapshot){this.snapshot=snapshot;
    if(this.activityTimer===undefined)this.activityTimer=this.schedule(()=>{this.activityTimer=undefined;this.emit('activity',{items:this.snapshot()});},Math.min(1600,400+this.pressure()*250));
  }
  close(){if(this.previewTimer!==undefined)this.cancel(this.previewTimer);if(this.activityTimer!==undefined)this.cancel(this.activityTimer);this.pending.clear();}
}
