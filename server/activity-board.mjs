import {activityPreview,latestMessage} from './thread-previews.mjs';
export class ActivityBoard {
  constructor(clock=()=>Date.now()){this.clock=clock;this.items=new Map();this.sequence=0;}
  observe(event){
    const p=event.params||{},threadId=p.threadId||p.thread?.id;if(!threadId)return;
    if(event.method==='turn/completed'){for(const [key,item] of this.items)if(item.threadId===threadId&&item.state==='working')this.items.set(key,{...item,state:'finished',at:this.clock(),sequence:++this.sequence});return;}
    let item=p.item,itemId=item?.id||p.itemId||p.turn?.id;
    if(!itemId)return;
    const key=threadId+':'+itemId,old=this.items.get(key);let preview;
    if(event.method==='item/reasoning/summaryTextDelta'){
      const index=Number.isInteger(p.summaryIndex)&&p.summaryIndex>=0&&p.summaryIndex<100?p.summaryIndex:0;
      const parts=[...(old?.thinkingParts||[])];parts[index]=((parts[index]||'')+String(p.delta||'')).slice(-240);
      preview={...activityPreview({type:'reasoning',status:'inProgress',summary:parts}),thinkingParts:parts};
    }else if(event.method==='item/reasoning/summaryPartAdded')preview=activityPreview({type:'reasoning',status:'inProgress'});
    else if(event.method==='item/agentMessage/delta')preview={preview:((old?.previewRole==='assistant'?old.preview:'')+String(p.delta||'')).slice(-280),previewRole:'assistant',previewKind:'message'};
    else if(event.method==='item/commandExecution/outputDelta'&&old)preview={...old,preview:old.summary+' · '+String(p.delta||'').trim().split(/\r?\n/).filter(Boolean).at(-1)};
    else if(['item/started','item/completed'].includes(event.method)&&item){item=event.method==='item/started'?{...item,status:'inProgress'}:item;preview=latestMessage({turns:[{items:[item]}]})||activityPreview(item);}
    if(!preview?.preview||preview.previewKind==='thinking')return;
    const state=event.method==='item/completed'?(item?.status==='failed'?'failed':'finished'):'working';
    this.items.set(key,{id:key,threadId,itemId,preview:preview.preview.slice(0,280),previewRole:preview.previewRole,previewKind:preview.previewKind||'message',summary:old?.summary||preview.preview,thinkingParts:preview.thinkingParts??old?.thinkingParts,state,at:this.clock(),sequence:++this.sequence});
    this.prune();
  }
  prune(){const now=this.clock();for(const [id,item]of this.items)if(item.previewKind==='thinking'||item.state!=='working'&&now-item.at>90000)this.items.delete(id);while(this.items.size>100)this.items.delete(this.items.keys().next().value);}
  snapshot(){this.prune();return [...this.items.values()].sort((a,b)=>b.at-a.at||b.sequence-a.sequence);}
}
