import {displayItem} from './timeline.mjs';

const unsupportedItems=e=>/thread\/items\/list.*not supported|list_items.*not supported/i.test(e.message||'');
const encode=(prefix,value)=>prefix+Buffer.from(JSON.stringify(value)).toString('base64url');
export class ThreadHistory {
  constructor(codex,{maxItems=400,maxBytes=1024*1024,onPreview=()=>{},inheritedHistory=()=>[]}={}){this.onPreview=onPreview;this.inheritedHistory=inheritedHistory;this.turnOnlyThreads=new Set();this.fullSnapshotThreads=new Set();Object.assign(this,{codex,maxItems,maxBytes:Math.max(64000,maxBytes)});}
  async preview(metadata){
    const thread=await this.read(metadata,{summary:true});this.onPreview(thread);
    if(this.fullSnapshotThreads.has(metadata.id))return thread;
    const latest=thread.turns?.at(-1);
    if(!latest||metadata.historyMode!=='paginated'||this.paginationSupported!==true)return thread;
    // Turn summaries omit action/reasoning items. Read only the newest bounded item page,
    // without resuming a desktop-owned conversation or downloading its whole transcript.
    if(this.turnOnlyThreads.has(metadata.id))return this.turnPage(metadata,{maxItems:6});
    let page;try{page=await this.codex.call('thread/items/list',{threadId:metadata.id,turnId:latest.id,limit:6,sortDirection:'desc'});}catch(e){if(!unsupportedItems(e))throw e;this.turnOnlyThreads.add(metadata.id);return this.turnPage(metadata,{maxItems:6});}
    return {...thread,turns:[{...latest,items:[...page.data].reverse().map(entry=>displayItem(entry.item,latest.id)).filter(Boolean)}]};
  }
  async read(metadata,options={}){
    if(this.fullSnapshotThreads.has(metadata.id))return this.fullSnapshotPage(metadata,options);
    if(this.turnOnlyThreads.has(metadata.id)&&!options.summary)return this.turnPage(metadata,options);
    try{return await this.readPaged(metadata,options);}
    catch(e){if(!unsupportedItems(e))throw e;this.turnOnlyThreads.add(metadata.id);return this.turnPage(metadata,options);}
  }
  async readPaged(metadata,{before=null,summary=false,maxItems=this.maxItems,maxBytes=this.maxBytes,maxTurns=8,preferPaging=false}={}){
    const threadId=metadata.id;
    if(metadata.historyMode!=='paginated'&&!preferPaging)return (await this.codex.call('thread/read',{threadId,includeTurns:true})).thread;
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
    for(let turnCount=0;turnCount<maxTurns;turnCount++){
      const resumed=!!state.anchor;
      const headers=await this.codex.call('thread/turns/list',{threadId,limit:1,sortDirection:resumed?'asc':'desc',itemsView:'notLoaded',...((state.anchor||state.nextTurn)?{cursor:state.anchor||state.nextTurn}:{})});
      const header=headers.data[0];if(!header)return finish(null);
      const anchor=resumed?state.anchor:headers.backwardsCursor;
      const nextTurn=resumed?state.nextTurn:headers.nextCursor;
      const turn={...header,items:[],_pocketHideEnd:resumed&&!!state.itemCursor};turns.push(turn);
      let cursor=state.itemCursor||null;
      while(true){
        const key=JSON.stringify([header.id,cursor]);if(visited.has(key))throw Error('Codex returned a repeated history cursor.');visited.add(key);
        const limit=Math.max(1,Math.min(8,maxItems,Math.floor(maxBytes/64000)));
        const part=await this.codex.call('thread/items/list',{threadId,turnId:header.id,limit,sortDirection:'desc',...(cursor?{cursor}:{})});
        const items=part.data.map(entry=>displayItem(entry.item,header.id)).filter(Boolean);
        const size=items.reduce((n,item)=>n+Buffer.byteLength(JSON.stringify(item)),0);
        if(count&&(count+items.length>maxItems||bytes+size>maxBytes)){
          turn.items.reverse();if(!turn.items.length)turns.pop();
          if(!anchor)throw Error('Codex did not provide a history anchor. Update Codex to continue paging.');
          return finish({anchor,nextTurn,itemCursor:cursor});
        }
        turn.items.push(...items);count+=items.length;bytes+=size;
        if(!part.nextCursor)break;
        cursor=part.nextCursor;
        if(count>=maxItems||bytes>=maxBytes){
          turn.items.reverse();
          if(!anchor)throw Error('Codex did not provide a history anchor. Update Codex to continue paging.');
          return finish({anchor,nextTurn,itemCursor:cursor});
        }
      }
      turn.items.reverse();
      if(!nextTurn)return finish(null);
      state={nextTurn};
      if(count>=maxItems||bytes>=maxBytes)return finish(state);
    }
    return finish(state);
  }
  async turnPage(metadata,{before=null,maxItems=this.maxItems,maxBytes=this.maxBytes}={}){
    let state={cursor:null,offset:0};
    if(before){
      if(typeof before!=='string'||before.length>16000)throw Error('Invalid history position. Reopen the task.');
      if(before.startsWith('turnfull:')){
        try{state=JSON.parse(Buffer.from(before.slice(9),'base64url').toString());}catch{throw Error('Invalid history position. Reopen the task.');}
        if(!state||!Number.isSafeInteger(state.offset)||state.offset<0||(state.cursor!=null&&typeof state.cursor!=='string'))throw Error('Invalid history position. Reopen the task.');
      }else if(before.startsWith('page:'))state.cursor=Buffer.from(before.slice(5),'base64url').toString();
      else throw Error('That history position changed with Codex. Reopen the task.');
    }
    const page=await this.codex.call('thread/turns/list',{threadId:metadata.id,limit:1,sortDirection:'desc',itemsView:'full',...(state.cursor?{cursor:state.cursor}:{})});
    const turn=page.data[0];
    if(!turn)return {...metadata,turns:[],_pocketPage:{hasEarlier:false,before:null}};
    const items=(turn.items||[]).map(item=>displayItem(item,turn.id)).filter(Boolean);
    const end=Math.max(0,items.length-state.offset);let start=end,bytes=0;
    while(start>0&&end-start<maxItems){const size=Buffer.byteLength(JSON.stringify(items[start-1]));if(start<end&&bytes+size>maxBytes)break;bytes+=size;start--;}
    const next=start>0?{cursor:state.cursor,offset:items.length-start}:page.nextCursor?{cursor:page.nextCursor,offset:0}:null;
    return {...metadata,turns:[{...turn,items:items.slice(start,end),_pocketHideEnd:state.offset>0}],_pocketPage:{hasEarlier:!!next,before:next?encode('turnfull:',next):null}};
  }
  async legacyPage(threadId,metadata,before){
    if(before)return {...metadata,turns:[],_pocketPage:{hasEarlier:false,before:null}};
    const thread=(await this.codex.call('thread/read',{threadId,includeTurns:true})).thread;
    return {...thread,_pocketPage:{hasEarlier:false,before:null}};
  }
  async fullSnapshotPage(metadata,options={}){
    const {before=null}=options,archive=this.inheritedHistory(metadata.id);
    if(before?.startsWith('nativefull:'))return this.archivePage(metadata,archive,options);
    let native;
    if(this.turnOnlyThreads.has(metadata.id)&&!options.summary)native=await this.turnPage(metadata,options);
    else try{native=await this.readPaged(metadata,{...options,preferPaging:true});}catch(e){if(!unsupportedItems(e))throw e;this.turnOnlyThreads.add(metadata.id);native=await this.turnPage(metadata,options);}
    if(native._pocketPage?.hasEarlier||!archive.length)return native;
    const current=new Set((native.turns||[]).map(t=>t.rootTurnId||t.id)),earlier=archive.filter(t=>!current.has(t.rootTurnId||t.id));
    if(!earlier.length)return native;
    if(!native.turns?.length)return this.archivePage(metadata,earlier,options);
    const last=earlier.at(-1);return {...native,_pocketPage:{hasEarlier:true,before:encode('nativefull:',{turnId:last.id,itemId:null})}};
  }
  archivePage(metadata,turns,{before=null,maxItems=this.maxItems,maxBytes=this.maxBytes,maxTurns=8}={}){
    let index=turns.length-1,end=null;
    if(before){if(typeof before!=='string'||before.length>16000||!before.startsWith('nativefull:'))throw Error('That history position changed with Codex. Reopen the task.');
      let state;try{state=JSON.parse(Buffer.from(before.slice(11),'base64url').toString());}catch{throw Error('Invalid history position.');}
      if(!state||typeof state.turnId!=='string'||state.itemId!=null&&typeof state.itemId!=='string')throw Error('Invalid history position.');
      index=turns.findIndex(t=>t.id===state.turnId);if(index<0)throw Error('The native history boundary is no longer available.');
      if(state.itemId!=null){end=(turns[index].items||[]).map(item=>displayItem(item,turns[index].id)).filter(Boolean).findIndex(item=>item.id===state.itemId);if(end<0)throw Error('The native history item boundary is no longer available.');}
    }
    const selected=[];let count=0,bytes=0,next=null;
    for(let n=0;index>=0&&n<maxTurns;n++,index--){const t=turns[index],items=(t.items||[]).map(item=>displayItem(item,t.id)).filter(Boolean);let stop=end??items.length,start=stop;
      while(start>0&&count<maxItems){const size=Buffer.byteLength(JSON.stringify(items[start-1]));if(count&&bytes+size>maxBytes)break;bytes+=size;count++;start--;}
      selected.unshift({...t,items:items.slice(start,stop),_pocketHideEnd:end!=null});
      if(start>0){if(!items[start]?.id)throw Error('Codex did not provide a stable item identity.');next={turnId:t.id,itemId:items[start].id};break;}
      end=null;
      if(index>0&&(count>=maxItems||bytes>=maxBytes||n+1>=maxTurns)){next={turnId:turns[index-1].id,itemId:null};break;}
    }
    return {...metadata,turns:selected,_pocketPage:{hasEarlier:!!next,before:next?encode('nativefull:',next):null}};
  }
}
