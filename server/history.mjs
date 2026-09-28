import {itemRow} from './timeline.mjs';

// Never hydrate an entire paginated conversation in a single app-server frame.
export class ThreadHistory {
  constructor(codex){this.codex=codex;}
  async read(metadata,{before=null,summary=false}={}){
    const threadId=metadata.id;
    if(metadata.historyMode!=='paginated')return (await this.codex.call('thread/read',{threadId,includeTurns:true})).thread;
    let cursor;
    if(before){
      if(typeof before!=='string'||!before.startsWith('page:')||before.length>16000)throw Error('That history position is no longer available. Reopen the task.');
      cursor=Buffer.from(before.slice(5),'base64url').toString();
    }
    const page=await this.codex.call('thread/turns/list',{threadId,limit:8,sortDirection:'desc',itemsView:summary?'summary':'notLoaded',...(cursor?{cursor}:{})});
    const turns=[...page.data].reverse();
    if(!summary)for(const turn of turns){
      const items=[];let next=null;const visited=new Set();
      do{
        const part=await this.codex.call('thread/items/list',{threadId,turnId:turn.id,limit:20,sortDirection:'asc',...(next?{cursor:next}:{})});
        for(const entry of part.data){
          const item=entry.item,row=itemRow(item,turn.id);
          // Keep display content, not inline images, raw reasoning, or unbounded tool output.
          if(row)items.push({id:item.id,type:item.type,status:item.status,phase:item.phase,text:item.type==='agentMessage'?item.text:undefined,_pocketRow:row});
        }
        next=part.nextCursor;
        if(next&&visited.has(next))throw Error('Codex returned a repeated history cursor. Reopen the task to retry.');
        if(next)visited.add(next);
      }while(next);
      turn.items=items;
    }
    return {...metadata,turns,_pocketPage:{hasEarlier:!!page.nextCursor,before:page.nextCursor?'page:'+Buffer.from(page.nextCursor).toString('base64url'):null}};
  }
}
