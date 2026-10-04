import {displayItem} from './timeline.mjs';

const encode=(prefix,value)=>prefix+Buffer.from(JSON.stringify(value)).toString('base64url');
export class ThreadHistory {
  constructor(codex,{maxItems=400,maxBytes=1024*1024,onPreview=()=>{}}={}){this.onPreview=onPreview;Object.assign(this,{codex,maxItems,maxBytes:Math.max(64000,maxBytes)});}
  async preview(metadata){
    const thread=await this.read(metadata,{summary:true});this.onPreview(thread);
    const latest=thread.turns?.at(-1);
    if(!latest||metadata.historyMode!=='paginated'||this.paginationSupported!==true)return thread;
    // Turn summaries omit action/reasoning items. Read only the newest bounded item page,
    // without resuming a desktop-owned conversation or downloading its whole transcript.
    const page=await this.codex.call('thread/items/list',{threadId:metadata.id,turnId:latest.id,limit:6,sortDirection:'desc'});
    return {...thread,turns:[{...latest,items:[...page.data].reverse().map(entry=>displayItem(entry.item,latest.id)).filter(Boolean)}]};
  }
  async read(metadata,{before=null,summary=false}={}){
    const threadId=metadata.id;
    if(metadata.historyMode!=='paginated')return (await this.codex.call('thread/read',{threadId,includeTurns:true})).thread;
    if(this.paginationSupported!==true){
      if(this.paginationSupported===false)return this.legacyPage(threadId,metadata,before);
      try{await this.codex.call('thread/turns/list',{threadId,limit:1,sortDirection:'desc',itemsView:'notLoaded'});this.paginationSupported=true;}
      catch(e){
        if(!/list_turns|thread\/turns\/list.*not supported|not supported yet/i.test(e.message))throw e;
        this.paginationSupported=false;return this.legacyPage(threadId,metadata,before);
      }
    }
    if(summary){
      const cursor=before?Buffer.from(before.slice(5),'base64url').toString():null;
      const page=await this.codex.call('thread/turns/list',{threadId,limit:8,sortDirection:'desc',itemsView:'summary',...(cursor?{cursor}:{})});
      return {...metadata,turns:[...page.data].reverse(),_pocketPage:{hasEarlier:!!page.nextCursor,before:page.nextCursor?'page:'+Buffer.from(page.nextCursor).toString('base64url'):null}};
    }
    let state={nextTurn:null};
    if(before){
      if(typeof before!=='string'||before.length>16000)throw Error('Invalid history position. Reopen the task.');
      if(before.startsWith('items:')){
        try{state=JSON.parse(Buffer.from(before.slice(6),'base64url').toString());}catch{throw Error('Invalid history position. Reopen the task.');}
        if(!state||typeof state!=='object'||Array.isArray(state)||['anchor','nextTurn','itemCursor'].some(k=>state[k]!=null&&typeof state[k]!=='string'))throw Error('Invalid history position. Reopen the task.');
      }else if(before.startsWith('page:'))state={nextTurn:Buffer.from(before.slice(5),'base64url').toString()};
      else throw Error('That history position is no longer available. Reopen the task.');
    }
    const turns=[];let count=0,bytes=0;const visited=new Set();
    const finish=next=>({...metadata,turns:turns.reverse(),_pocketPage:{hasEarlier:!!next,before:next?encode('items:',next):null}});
    for(let turnCount=0;turnCount<8;turnCount++){
      const resumed=!!state.anchor;
      const headers=await this.codex.call('thread/turns/list',{threadId,limit:1,sortDirection:resumed?'asc':'desc',itemsView:'notLoaded',...((state.anchor||state.nextTurn)?{cursor:state.anchor||state.nextTurn}:{})});
      const header=headers.data[0];if(!header)return finish(null);
      const anchor=resumed?state.anchor:headers.backwardsCursor;
      const nextTurn=resumed?state.nextTurn:headers.nextCursor;
      const turn={...header,items:[],_pocketHideEnd:resumed&&!!state.itemCursor};turns.push(turn);
      let cursor=state.itemCursor||null;
      while(true){
        const key=JSON.stringify([header.id,cursor]);if(visited.has(key))throw Error('Codex returned a repeated history cursor.');visited.add(key);
        const limit=Math.max(1,Math.min(8,this.maxItems,Math.floor(this.maxBytes/64000)));
        const part=await this.codex.call('thread/items/list',{threadId,turnId:header.id,limit,sortDirection:'desc',...(cursor?{cursor}:{})});
        const items=part.data.map(entry=>displayItem(entry.item,header.id)).filter(Boolean);
        const size=items.reduce((n,item)=>n+Buffer.byteLength(JSON.stringify(item)),0);
        if(count&&(count+items.length>this.maxItems||bytes+size>this.maxBytes)){
          turn.items.reverse();if(!turn.items.length)turns.pop();
          if(!anchor)throw Error('Codex did not provide a history anchor. Update Codex to continue paging.');
          return finish({anchor,nextTurn,itemCursor:cursor});
        }
        turn.items.push(...items);count+=items.length;bytes+=size;
        if(!part.nextCursor)break;
        cursor=part.nextCursor;
      }
      turn.items.reverse();
      if(!nextTurn)return finish(null);
      state={nextTurn};
      if(count>=this.maxItems||bytes>=this.maxBytes)return finish(state);
    }
    return finish(state);
  }
  async legacyPage(threadId,metadata,before){
    if(before)return {...metadata,turns:[],_pocketPage:{hasEarlier:false,before:null}};
    const thread=(await this.codex.call('thread/read',{threadId,includeTurns:true})).thread;
    return {...thread,_pocketPage:{hasEarlier:false,before:null}};
  }
}
