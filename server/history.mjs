import {createHash} from 'node:crypto';
import {displayItem} from './timeline.mjs';
import {HistoryPositionIndex} from './history-position-index.mjs';

const unsupportedItems=e=>/thread\/items\/list.*not supported|list_items.*not supported/i.test(e.message||'');
const encode=(prefix,value)=>prefix+Buffer.from(JSON.stringify(value)).toString('base64url');
export class ThreadHistory {
  constructor(codex,{maxItems=400,maxBytes=1024*1024,onPreview=()=>{},identity=null,inheritedHistory=()=>[]}={}){this.identity=identity;this.positions=new HistoryPositionIndex();codex.on?.('disconnected',()=>this.positions.clear());this.onPreview=onPreview;this.inheritedHistory=inheritedHistory;this.turnOnlyThreads=new Set();this.fullSnapshotThreads=new Set();Object.assign(this,{codex,maxItems,maxBytes:Math.max(64000,maxBytes)});}
  observeSnapshot(thread){for(const turn of (thread.turns||[]).slice(-32))this.positions.observe(thread.id,turn.id,(turn.items||[]).map(item=>displayItem(item,turn.id)).filter(Boolean));return thread;}
  // Resolve chronology without downloading older transcript pages or resuming work.
  // Each request reads at most four small provider pages; a continuation retains
  // the exact target and provider cursor, rather than restarting from the tail.
  async locateItemSuccessor(threadId,{turnId,itemId,clientId=null,knownItemIds,cursor=null}={}){
    const invalid=()=>Object.assign(Error('Invalid item position.'),{status:400});
    const validId=v=>typeof v==='string'&&/^[a-zA-Z0-9_-]{1,256}$/.test(v);
    if(!validId(threadId)||!validId(turnId)||!validId(itemId)||!Array.isArray(knownItemIds)||knownItemIds.length>256||knownItemIds.some(id=>!validId(id)))throw invalid();
    if(clientId!=null&&!validId(clientId))throw invalid();
    if(cursor!=null&&(typeof cursor!=='string'||cursor.length>16000))throw invalid();
    const observed=this.positions.resolve(threadId,turnId,{itemId,clientId,knownItemIds});if(observed)return observed;
    if(cursor?.startsWith('position-scan:'))return this.scanItemSuccessor(threadId,{turnId,itemId,clientId,knownItemIds,cursor});
    if(this.itemAnchorsSupported===false&&!this.turnOnlyThreads.has(threadId))
      return this.scanItemSuccessor(threadId,{turnId,itemId,clientId,knownItemIds});
    let next={type:'item',itemId};
    if(cursor!=null){
      if(typeof cursor!=='string'||cursor.length>16000||!cursor.startsWith('position:'))throw invalid();
      let state;try{state=JSON.parse(Buffer.from(cursor.slice(9),'base64url').toString());}catch{throw invalid();}
      if(state?.threadId!==threadId||state.turnId!==turnId||state.itemId!==itemId||typeof state.next!=='string'||!state.next)throw invalid();
      next=state.next;
    }
    if(this.itemAnchorsSupported===false||this.turnOnlyThreads.has(threadId))return {available:false,insertBefore:null,cursor:null,...(clientId&&!this.positions.has(threadId,turnId)?{awaitingHistory:true}:{})};
    const known=new Set(knownItemIds);known.delete(itemId);const visited=new Set();
    for(let n=0;n<4;n++){
      const key=JSON.stringify(next);if(visited.has(key))throw Error('Codex returned a repeated item position.');visited.add(key);
      let page;
      try{page=await this.codex.call('thread/items/list',{threadId,turnId,cursor:next,sortDirection:'asc',limit:8});}
      catch(e){
        if(unsupportedItems(e)||/invalid type: map, expected a string/i.test(e.message||'')){
          if(!unsupportedItems(e)){
            this.itemAnchorsSupported=false;
            return this.scanItemSuccessor(threadId,{turnId,itemId,clientId,knownItemIds});
          }
          return {available:false,insertBefore:null,cursor:null};
        }
        throw e;
      }
      for(const entry of page.data||[]){
        if(entry.turnId!=null&&entry.turnId!==turnId)throw Error('Codex returned an item outside the requested turn.');
        if(known.has(entry.item?.id)&&displayItem(entry.item,turnId))return {available:true,insertBefore:`${turnId}/${entry.item.id}`,cursor:null};
      }
      if(!page.nextCursor)return {available:true,insertBefore:null,cursor:null};
      next=page.nextCursor;
    }
    return {available:true,insertBefore:null,cursor:encode('position:',{threadId,turnId,itemId,next})};
  }
  // Some native versions accept only opaque list cursors, not item anchors.
  // Walk bounded descending pages for this exact input, carrying the nearest
  // already-visible successor across continuations. This never resumes a thread
  // and never sorts by arrival time, text or opaque item IDs.
  async scanItemSuccessor(threadId,{turnId,itemId,clientId,knownItemIds,cursor=null}){
    const known=new Set(knownItemIds);known.delete(itemId);
    const digest=createHash('sha256').update(JSON.stringify([...known].sort())).digest('hex');
    let next=null,successor=null;
    if(cursor){
      let state;try{state=JSON.parse(Buffer.from(cursor.slice(14),'base64url').toString());}catch{throw Object.assign(Error('Invalid item position.'),{status:400});}
      if(state.threadId!==threadId||state.turnId!==turnId||state.itemId!==itemId||state.clientId!==clientId||state.knownDigest!==digest||
          typeof state.next!=='string'||!state.next||state.successor!=null&&!known.has(state.successor))
        throw Object.assign(Error('Invalid item position.'),{status:400});
      next=state.next;successor=state.successor;
    }
    const visited=new Set();
    for(let n=0;n<4;n++){
      if(visited.has(next))throw Error('Codex returned a repeated item position.');visited.add(next);
      let page;
      try{page=await this.codex.call('thread/items/list',{threadId,turnId,sortDirection:'desc',limit:8,...(next?{cursor:next}:{})});}
      catch(error){
        if(unsupportedItems(error)||/invalid type: map, expected a string/i.test(error.message||''))
          return {available:false,insertBefore:null,cursor:null};
        throw error;
      }
      for(const entry of page.data||[]){
        if(entry.turnId!=null&&entry.turnId!==turnId)throw Error('Codex returned an item outside the requested turn.');
        const raw=entry.item;if(!raw)continue;
        const item=this.identity?.item(threadId,turnId,raw)||raw;
        const row=item._pocketRow||item;
        const same=item.type==='userMessage'&&(clientId?(row.clientId||row.clientUserMessageId)===clientId:raw.id===itemId);
        if(same)return {available:true,insertBefore:successor?turnId+'/'+successor:null,cursor:null};
        if(known.has(raw.id)&&displayItem(item,turnId))successor=raw.id;
      }
      if(!page.nextCursor)return {available:false,insertBefore:null,cursor:null,awaitingHistory:true};
      next=page.nextCursor;
    }
    return {available:false,insertBefore:null,cursor:encode('position-scan:',{threadId,turnId,itemId,clientId,knownDigest:digest,next,successor})};
  }
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
    let thread;
    if(this.fullSnapshotThreads.has(metadata.id))thread=await this.fullSnapshotPage(metadata,options);
    else if(this.turnOnlyThreads.has(metadata.id)&&!options.summary)thread=await this.turnPage(metadata,options);
    else try{thread=await this.readPaged(metadata,options);}
    catch(e){if(!unsupportedItems(e))throw e;this.turnOnlyThreads.add(metadata.id);thread=await this.turnPage(metadata,options);}
    const display=this.identity?.snapshot(thread)||thread;
    // Turn-only reads already indexed the complete provider turn. This result
    // is only a bounded presentation window, including inherited-history paths.
    return this.turnOnlyThreads.has(metadata.id)?display:this.observeSnapshot(display);
  }

  async readPaged(metadata,{before=null,summary=false,maxItems=this.maxItems,maxBytes=this.maxBytes,maxTurns=8,preferPaging=false,maxSourcePages=8,stopAfterConversation=false}={}){
    const threadId=metadata.id;
    if(metadata.historyMode!=='paginated'&&!preferPaging)return this.observeSnapshot((await this.codex.call('thread/read',{threadId,includeTurns:true})).thread);
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
        if(!state||typeof state!=='object'||Array.isArray(state)||['anchor','nextTurn','itemCursor','olderThanRow'].some(k=>state[k]!=null&&typeof state[k]!=='string'))throw Error('Invalid history position. Reopen the task.');
      }else if(before.startsWith('page:'))state={nextTurn:Buffer.from(before.slice(5),'base64url').toString()};
      else throw Error('That history position is no longer available. Reopen the task.');
    }
    const insertBefore=state.olderThanRow||null;
    const turns=[];let count=0,bytes=0,sourcePages=0;const visited=new Set();
    const hasConversation=()=>stopAfterConversation&&turns.some(turn=>turn.items.some(item=>['userMessage','agentMessage','plan'].includes(item.type)));
    const finish=next=>({...metadata,turns:turns.reverse(),_pocketPage:{hasEarlier:!!next,before:next?encode('items:',next):null,...(insertBefore?{insertBefore}:{})}});
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
        sourcePages++;
        const items=part.data.map(entry=>displayItem(entry.item,header.id)).filter(Boolean);
        const size=items.reduce((n,item)=>n+Buffer.byteLength(JSON.stringify(item)),0);
        if(count&&(count+items.length>maxItems||bytes+size>maxBytes)){
          turn.items.reverse();if(!turn.items.length)turns.pop();
          if(!anchor)throw Error('Codex did not provide a history anchor. Update Codex to continue paging.');
          return finish({anchor,nextTurn,itemCursor:cursor,olderThanRow:turn.items.length?`${header.id}/${turn.items[0].id}`:turns.at(-1)?`${turns.at(-1).id}/header`:null});
        }
        turn.items.push(...items);count+=items.length;bytes+=size;
        if(!part.nextCursor)break;
        cursor=part.nextCursor;
        if(count>=maxItems||bytes>=maxBytes||sourcePages>=maxSourcePages||hasConversation()){
          turn.items.reverse();
          if(!anchor)throw Error('Codex did not provide a history anchor. Update Codex to continue paging.');
          return finish({anchor,nextTurn,itemCursor:cursor,olderThanRow:turn.items.length?`${header.id}/${turn.items[0].id}`:turns.at(-1)?`${turns.at(-1).id}/header`:null});
        }
      }
      turn.items.reverse();
      if(!nextTurn)return finish(null);
      state={nextTurn,olderThanRow:`${header.id}/header`};
      if(count>=maxItems||bytes>=maxBytes||sourcePages>=maxSourcePages||hasConversation())return finish(state);
    }
    return finish(state);
  }
  async turnPage(metadata,{before=null,maxItems=this.maxItems,maxBytes=this.maxBytes}={}){
    let state={cursor:null,offset:0};
    if(before){
      if(typeof before!=='string'||before.length>16000)throw Error('Invalid history position. Reopen the task.');
      if(before.startsWith('turnfull:')){
        try{state=JSON.parse(Buffer.from(before.slice(9),'base64url').toString());}catch{throw Error('Invalid history position. Reopen the task.');}
        if(!state||!Number.isSafeInteger(state.offset)||state.offset<0||['cursor','anchor','nextTurn','turnId','beforeItemId','olderThanRow'].some(k=>state[k]!=null&&typeof state[k]!=='string'))throw Error('Invalid history position. Reopen the task.');
      }else if(before.startsWith('page:'))state.cursor=Buffer.from(before.slice(5),'base64url').toString();
      else throw Error('That history position changed with Codex. Reopen the task.');
    }
    const page=await this.codex.call('thread/turns/list',{threadId:metadata.id,limit:1,sortDirection:state.anchor?'asc':'desc',itemsView:'full',...((state.anchor||state.cursor)?{cursor:state.anchor||state.cursor}:{})});
    const providerTurn=page.data[0];
    if(!providerTurn)return {...metadata,turns:[],_pocketPage:{hasEarlier:false,before:null}};
    if(state.turnId&&providerTurn.id!==state.turnId)throw Error('The history turn boundary is no longer available. Reopen the task.');
    // Send identity and chronological evidence belong to the complete provider
    // turn, before the wire page is bounded. Re-observing that bounded result
    // would discard the very input needed to position a delayed live echo.
    const turn=this.identity?.snapshot({id:metadata.id,turns:[providerTurn]})?.turns[0]||providerTurn;
    const items=(turn.items||[]).map(item=>displayItem(item,turn.id)).filter(Boolean);
    this.positions.observe(metadata.id,turn.id,items);
    const end=state.beforeItemId?items.findIndex(item=>item.id===state.beforeItemId):Math.max(0,items.length-state.offset);
    if(end<0)throw Error('The history item boundary is no longer available. Reopen the task.');
    let start=end,bytes=0;
    while(start>0&&end-start<maxItems){const size=Buffer.byteLength(JSON.stringify(items[start-1]));if(start<end&&bytes+size>maxBytes)break;bytes+=size;start--;}
    const nextTurn=state.anchor?state.nextTurn:page.nextCursor;
    const next=start>0?{cursor:state.cursor,offset:items.length-start,beforeItemId:items[start].id,turnId:turn.id,...((state.anchor||page.backwardsCursor)?{anchor:state.anchor||page.backwardsCursor,nextTurn:nextTurn||null}:{}),olderThanRow:`${turn.id}/${items[start].id}`}:
      nextTurn?{cursor:nextTurn,offset:0,olderThanRow:`${turn.id}/header`}:null;
    const insertBefore=state.olderThanRow||(state.offset>0&&items[end]?`${turn.id}/${items[end].id}`:null);
    return {...metadata,turns:[{...turn,items:items.slice(start,end),_pocketHideEnd:state.offset>0||!!state.beforeItemId}],_pocketPage:{hasEarlier:!!next,before:next?encode('turnfull:',next):null,...(insertBefore?{insertBefore}:{})}};
  }
  async legacyPage(threadId,metadata,before){
    if(before)return {...metadata,turns:[],_pocketPage:{hasEarlier:false,before:null}};
    const thread=this.observeSnapshot((await this.codex.call('thread/read',{threadId,includeTurns:true})).thread);
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
