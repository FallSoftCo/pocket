import {SessionStore} from './session-store.mjs';
import {animatedSymbol} from './symbol-motion.mjs';
import {ReadFeedback} from './read-feedback.mjs';
import {ImmersionLayer} from './immersion.mjs';
import {richText,foldRuns,usageSummary,sessionState,canonicalPage,ReadLanes} from './presentation.mjs';
import {sessionIdentity,workTime,compareWork,mergeRows,pendingFor} from './model.mjs';
import {read,write,clear} from './storage.mjs';
import {BrowserVoice} from './voice.mjs';
import {clientBase,clientURL} from './paths.mjs';
import {importPreviousProfile} from './migration.mjs';
const base=clientBase(import.meta.url);
function setLabel(node,text){const source=String(text??'');if(node.dataset.labelSource!==source){node.dataset.labelSource=source;node.textContent=source;}}
const $=id=>document.getElementById(id),el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)setLabel(n,text);return n;};
let scope=read('scope','coordinator'),deviceId='',threads=[],current=null,status=null,ws=null,authenticated=false,polling=false,installEvent=null,models=[],loadSerial=0,earlierBusy=false,reconnectTimer,follow=true;
let openedScope=null;const readerViews=new Map();
let seatOrder=[],deliveryFlight=null,startFlight=null;
const lanes=new ReadLanes();
const readFeedback=new ReadFeedback(state=>{const node=$('message-loading');node.hidden=!state.visible;if(state.visible)setLabel(node,state.initial?'Loading latest messages…':'Updating messages…');$('transcript').setAttribute('aria-busy',String(state.visible));});
readFeedback.select(scope);

const cache=new SessionStore(),receipts=new Set(),notifySeen=new Set();let pending=read('pending',null),newPending=read('newPending',null);
async function api(path,body,options={}){
 const method=options.method||(body===undefined?'GET':'POST'),binary=body instanceof Blob||body instanceof ArrayBuffer;
 const r=await fetch(clientURL(base,path),{method,credentials:'same-origin',headers:{'Content-Type':binary?'audio/wav':'application/json',...(options.headers||{})},...(body===undefined?{}:{body:binary?body:JSON.stringify(body)}),...(method==='GET'?{signal:AbortSignal.timeout(20000)}:{})});
 let data;try{data=await r.json();}catch{data={error:`Backend returned HTTP ${r.status}.`};}
 if(!r.ok){if(r.status===401&&authenticated){authenticated=false;ws?.close();$('app').hidden=true;$('pairing').hidden=false;}throw Object.assign(Error(data.error||`Request failed (${r.status}).`),{status:r.status});}return data;
}
const error=e=>{if($('startup')){$('startup').hidden=true;if($('app').hidden&&$('pairing').hidden)$('pairing').hidden=false;}$('error').textContent=e.message||String(e);$('error').hidden=false;};
const run=fn=>async(...args)=>{try{$('error').hidden=true;await fn(...args);}catch(e){error(e);}};
const voice=new BrowserVoice(api,(text,p)=>{
 const holder=$('voice-status');holder.hidden=!text&&!voice.records.some(r=>!['cancelled','submitted','completed'].includes(r.state));holder.replaceChildren();if(text&&!voice.records.some(r=>r.error===text))holder.append(el('span','',text));
 if(voice.recording)holder.append(button('Cancel recording',()=>voice.discard(voice.recording.id)));
 if(text.includes('speech'))holder.append(button('Stop speech',()=>voice.disconnect()));
 for(const record of voice.records.filter(r=>!['cancelled','submitted','completed'].includes(r.state))){const row=el('div','recording-receipt',`${record.scope===scope?'This conversation':'Another conversation'} · ${record.error||record.state}`);if(!voice.jobs.has(record.id)&&record.state!=='unknown')row.append(button('Send saved recording',()=>voice.sendRecord(record.id)));row.append(button('Cancel',()=>voice.discard(record.id)),button('Forget local copy',()=>{if(confirm('This removes only the local recording. Any submitted work and original server receipt remain. Forget this copy?'))return voice.forget(record.id);}));if(record.scope!==scope)row.append(button('Open original conversation',()=>open(record.scope)));holder.append(row);}
 $('mic').classList.toggle('recording',!!voice.recording);$('mic').textContent=voice.recording?'■ Stop and send':'● Record';$('voice-send').hidden=true;$('voice-clear').hidden=true;
});
function draftKey(s=scope){return `draft.${deviceId}.${s}`;}
function saveDraft(){if(deviceId&&openedScope===scope)write(draftKey(),$('draft').value);}
function persistPending(value){pending=value;write('pending',value);renderOutbox();}
function button(label,action){const b=el('button','',label);b.type='button';b.onclick=run(action);return b;}
function stamp(ms){if(!ms)return 'Recency unknown';const d=Math.max(0,Date.now()-ms);return d<60000?'Just now':d<3600000?`${Math.floor(d/60000)}m ago`:d<86400000?`${Math.floor(d/3600000)}h ago`:new Date(ms).toLocaleDateString();}
let listReadingUntil=0,lastSeatRefresh=0;const expandedParents=new Set();
function renderList(){
 const query=$('search').value.toLowerCase(),filter=$('filter').value,children=new Map();
 for(const t of threads){const parent=sessionIdentity(t).parentThreadId;if(parent){if(!children.has(parent))children.set(parent,[]);children.get(parent).push(t);}}
 const visible=threads.filter(t=>(filter!=='active'||['active','pending'].includes(t.status?.type))&&[t.name,t.preview,t.cwd,t.agentNickname].some(x=>String(x||'').toLowerCase().includes(query)));
 const now=Date.now();if(!seatOrder.length||now>listReadingUntil&&now-lastSeatRefresh>5000){
  const prior=new Map(seatOrder.map((id,i)=>[id,i]));
  seatOrder=[...threads].sort((a,b)=>Math.floor(workTime(a)/10000)-Math.floor(workTime(b)/10000)||(prior.get(a.id)??1e6)-(prior.get(b.id)??1e6)||a.id.localeCompare(b.id)).map(t=>t.id);lastSeatRefresh=now;
 }for(const t of threads)if(!seatOrder.includes(t.id))seatOrder.push(t.id);
 const known=new Set(threads.map(t=>t.id)),ordered=[...visible].filter(t=>query||!known.has(sessionIdentity(t).parentThreadId)||expandedParents.has(sessionIdentity(t).parentThreadId)).sort((a,b)=>seatOrder.indexOf(a.id)-seatOrder.indexOf(b.id));
 const list=$('work-list'),anchor=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top),anchorOffset=anchor?.getBoundingClientRect().top,wasBottom=list.scrollHeight-list.scrollTop-list.clientHeight<60;
 const old=new Map([...list.children].map(n=>[n.dataset.id,n]));let cursor=list.firstChild;
 for(const t of ordered){let n=old.get(t.id);if(!n){n=el('article','work-card');n.dataset.id=t.id;n.tabIndex=0;n.setAttribute('role','button');n.onclick=run(()=>open(t.id));n.onkeydown=e=>{if(e.target===n&&['Enter',' '].includes(e.key)){e.preventDefault();void run(()=>open(t.id))();}};n.append(el('div','card-heading'),el('span','preview'),el('span','card-meta'));}
  old.delete(t.id);const identity=sessionIdentity(t),state=sessionState(t);n.className='work-card'+(scope===t.id?' selected':'')+(identity.isChild?' child':'');n.dataset.state=state.tone;n.setAttribute('aria-current',scope===t.id?'page':'false');
  n._thread=t;
  if(!n.querySelector('input')){let title=n.querySelector('.card-title');if(!title){title=button('',()=>renameCard(n._thread,n));title.className='card-title';title.addEventListener('click',e=>e.stopPropagation());n.children[0].prepend(title);}setLabel(title,t.name||t.agentNickname||'Untitled work');
   const agents=children.get(t.id)||[];let badge=n.querySelector('.child-badge');if(agents.length){if(!badge){badge=button('',()=>{expandedParents.has(t.id)?expandedParents.delete(t.id):expandedParents.add(t.id);renderList();});badge.className='child-badge';badge.addEventListener('click',e=>e.stopPropagation());n.children[0].append(badge);}setLabel(badge,`${agents.length} agent${agents.length===1?'':'s'}`);}else badge?.remove();}
  setLabel(n.children[1],t.preview||'Open conversation');if(!n.children[2].children.length)n.children[2].append(el('span','card-state'),el('span','card-age'));setLabel(n.children[2].children[0],state.label+(t.unreadCount?' · '+t.unreadCount+' unread':''));setLabel(n.children[2].children[1],stamp(workTime(t)));
  n.style.setProperty('--recency',`hsl(${state.tone==='idle'?275:state.tone==='attention'?38:205} ${Math.max(20,75-Math.log2(1+Math.max(0,now-workTime(t))/3600000)*7)}% 65%)`);
  if(n!==cursor)list.insertBefore(n,cursor);cursor=n.nextSibling;
 }for(const n of old.values())n.remove();
 if(wasBottom&&now>listReadingUntil)list.scrollTop=list.scrollHeight;else if(anchor?.isConnected&&anchorOffset!=null)list.scrollTop+=anchor.getBoundingClientRect().top-anchorOffset;
 if(!ordered.length)$('catalog-note').textContent='No work matches this view.';
}
async function renameCard(thread,card){
 const input=el('input');input.value=thread.name||'';input.setAttribute('aria-label','Session name');input.onclick=e=>e.stopPropagation();card.children[0].replaceChildren(input);input.focus();input.select();let finished=false;
 const finish=async save=>{if(finished)return;finished=true;try{const name=input.value.trim();if(save&&name&&name!==thread.name){await api(`/api/threads/${thread.id}/rename`,{name});cache.summary(thread.id,{name});threads=cache.list();}}catch(e){error(e);}finally{input.remove();renderList();}};
 input.onkeydown=e=>{e.stopPropagation();if(e.key==='Enter'){e.preventDefault();void finish(true);}else if(e.key==='Escape'){e.preventDefault();void finish(false);}};input.onblur=()=>void finish(true);
}
function renderHeader(){const t=scope==='coordinator'?null:current?.thread||threads.find(t=>t.id===scope),identity=sessionIdentity(t);setLabel($('title'),t?.name||t?.agentNickname||'Coordinator');$('context-label').textContent=t?identity.isChild?'DELEGATED WORK':'DIRECT SESSION':'DIRECT YOUR COMPUTING';setLabel($('session-state'),t?`${sessionState(t).label} · ${t.cwd||'Work environment'}${identity.isChild&&!identity.canAcceptDirectInput?' · Guide through the parent task':''}`:'Ask here, or open work to guide it directly.');$('coordinator').classList.toggle('active',scope==='coordinator');$('session-menu').hidden=!t||(identity.isChild&&!identity.canAcceptDirectInput);$('parent').hidden=!identity.parentThreadId;$('parent').onclick=run(()=>open(identity.parentThreadId));const enabled=scope==='coordinator'||!!current&&identity.canAcceptDirectInput;$('draft').disabled=!enabled;$('send').disabled=!enabled||!!deliveryFlight||!!pending;$('mic').disabled=!enabled||!voice.supported();$('draft').placeholder=!enabled?'Open the parent task to send guidance…':t?'Guide this session…':'Tell the coordinator what you need…';$('send-mode').hidden=!t;}
function rowNode(row){let n;if(['turn','turnEnd'].includes(row.kind))return el('div','turn-label',row.kind==='turn'?`${row.status||'Turn'}${row.startedAt?' · '+new Date(row.startedAt*1000).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}):''}`:`${row.status||'Finished'}${row.text?' · '+row.text:''}`);
 if(row.kind==='request')return el('div','turn-label','Decision needed — answer below');
 if(row.kind==='attachments'){n=el('div','message');n.append(el('header','',row.title));for(const a of row.attachments||[]){const b=button(a.name||'Open attachment',async()=>{if(!/^\/api\/files\/[a-zA-Z0-9_-]+$/.test(a.url))throw Error('Unsupported attachment URL.');window.open(clientURL(base,a.url),'_blank','noopener');});n.append(b);}return n;}
 if(row.kind==='runs'){const details=el('details','activity run-block');const actions=row.rows.filter(r=>r.kind==='activity');details.append(el('summary','',`${actions.length||row.rows.length} operations · ${actions.at(-1)?.title||row.rows.at(-1)?.status||'Work activity'}`));for(const item of row.rows){const entry=el('div','run-entry');entry.append(el('strong','',item.title||item.type||item.status||'Operation'));if(item.text||item.detail)entry.append(el('pre','',item.detail||item.text));details.append(entry);}details.ontoggle=()=>{$('collapse-runs').hidden=!document.querySelector('.run-block[open]');};return details;}
 if(row.kind==='activity'){n=el('details','activity');n.append(el('summary','',`${row.title||row.type} · ${row.text||row.status||''}`));if(row.detail)n.append(el('pre','',row.detail));return n;}
 n=el('article','message'+(row.kind==='user'?' user':''));const header=el('header');const icon=animatedSymbol(row.kind==='user'?'user':'codex',{size:28,label:row.kind==='user'?'You':'Codex'});header.append(icon);n.append(header);const body=el('div','body');body.dataset.immersionText=row.text||'';body.dataset.immersionId='web-message/'+row.id;body.append(richText(row.text));n.append(body);if(row.kind!=='user'){const actions=el('div','message-actions');actions.append(button('Reply to this',()=>{$('draft').value=`About this update: “${String(row.text||'').slice(0,220)}”\n\n${$('draft').value}`;saveDraft();$('draft').focus();}),button('Speak',()=>voice.speak(row.text)));n.append(actions);}return n;
}
function renderTranscript(){const data=cache.get(scope);if(!data)return;const list=$('transcript'),anchor=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top),anchorId=anchor?.dataset.id,anchorOffset=anchor?anchor.getBoundingClientRect().top-list.getBoundingClientRect().top:0,bottom=list.scrollHeight-list.scrollTop-list.clientHeight<90,previousHeight=list.scrollHeight,previousTop=list.scrollTop,important=$('important').checked;
 const sourceRows=scope==='coordinator'?(data.turns||[]).flatMap(t=>[{id:t.id+'/user',kind:'user',title:'You',text:t.transcript||'',turnId:t.id},{id:t.id+'/response',kind:'message',title:`Coordinator · ${t.state}`,text:t.response||t.error||'Working…',turnId:t.id,actions:t.actions}]):data.rows||[];
 const savedNotes=current?.notes||[];let visibleRows=sourceRows;
 if(important&&scope!=='coordinator'){
  visibleRows=sourceRows.filter(r=>r.kind==='request'||savedNotes.some(n=>(!activeNote||n.id===activeNote)&&(r.id===n.id||r.itemId===n.id||r.id.endsWith('/'+n.id))));
  for(const note of savedNotes.filter(n=>!activeNote||n.id===activeNote))if(!visibleRows.some(r=>r.id===note.id||r.itemId===note.id||r.id.endsWith('/'+note.id)))visibleRows.push({id:note.id,kind:'message',itemId:note.id,turnId:note.turnId,text:note.text});
 }
 const rows=foldRuns(visibleRows);
 const old=new Map([...list.children].map(n=>[n.dataset.id,n]));let cursor=list.firstChild;for(const row of rows){if(important&&!['message','user','request','attachments'].includes(row.kind))continue;let n=old.get(row.id);if(!samePresentation(n?._row,row)){const previous=n;n=rowNode(row);n.dataset.id=row.id;n._row=row;if(row.actions?.length){const actions=el('div','message-actions');for(const a of row.actions.filter(a=>a.threadId))actions.append(button(`Open ${a.name||'work'}`,()=>open(a.threadId)));n.append(actions);}if(previous){if(previous.open)n.open=true;previous.replaceWith(n);if(cursor===previous)cursor=n;}}old.delete(row.id);if(n!==cursor)list.insertBefore(n,cursor);cursor=n.nextSibling;}
 for(const n of old.values())n.remove();if(!rows.length){const empty=el('p','muted',important?'No saved answers in this conversation yet.':'No messages yet.');empty.dataset.id='empty';list.append(empty);}$('earlier').hidden=!data.hasEarlier;if(follow&&bottom)list.scrollTop=list.scrollHeight;else{const retained=anchorId&&[...list.children].find(n=>n.dataset.id===anchorId);if(retained)list.scrollTop+=retained.getBoundingClientRect().top-list.getBoundingClientRect().top-anchorOffset;else list.scrollTop=previousTop;}renderNotes();requestAnimationFrame(ackVisible);
}
function renderRequests(){const holder=$('requests');if(scope==='coordinator'){holder.replaceChildren();return;}const requests=current?.pending||[],signature=JSON.stringify(requests);if(holder.dataset.signature===signature)return;holder.dataset.signature=signature;holder.replaceChildren();for(const r of requests){const box=el('div','request-card');box.append(el('p','',r.params?.message||r.params?.command||'This session needs your decision.'));if(/requestUserInput$/.test(r.method)){const answers={};for(const q of r.params?.questions||[]){const label=el('label','',q.question),input=el('input');input.placeholder='Your answer';input.setAttribute('aria-label',q.question);label.append(input);box.append(label);for(const o of q.options||[])box.append(button(o.label,()=>{input.value=o.label;}));answers[q.id]=input;}box.append(button('Submit answers',async()=>{await api(`/api/requests/${encodeURIComponent(r.id)}/answer`,{answers:Object.fromEntries(Object.entries(answers).map(([id,n])=>[id,n.value]))});await refreshConversation();}),button('Skip',async()=>{await api(`/api/requests/${encodeURIComponent(r.id)}/answer`,{skip:true});await refreshConversation();}));}
 else if(/item\/(commandExecution|fileChange)\/requestApproval$/.test(r.method)){for(const decision of r.params?.availableDecisions||['accept','decline','cancel'])if(['accept','decline','cancel'].includes(decision))box.append(button({accept:'Allow once',decline:'Decline',cancel:'Cancel'}[decision],async()=>{await api(`/api/requests/${encodeURIComponent(r.id)}/answer`,{decision});await refreshConversation();}));}else box.append(el('p','muted','This request type must be answered in the terminal.'));holder.append(box);}}
function renderOutbox(){const holder=$('outbox');holder.replaceChildren();if(newPending){const row=el('div','outbox-row',`Task creation ${newPending.state||'pending'} · ${newPending.prompt.slice(0,80)}`);row.append(button('Check creation',reconcileStart));if(newPending.state==='unknown')row.append(button('Clear after reviewing work',()=>{if(confirm('Check recent work first. Clearing this receipt does not undo a created task; starting again could duplicate work. Clear it?')){newPending=null;write('newPending',null);renderOutbox();}}));holder.append(row);}if(pending){const row=el('div','outbox-row',`${pending.scope===scope?'':'Another conversation · '}Saved send · ${pending.text.slice(0,90)} — checking delivery`);row.append(button('Check delivery',reconcilePending));holder.append(row);}for(const o of current?.outgoing||[]){if(['accepted','cancelled'].includes(o.state))continue;const row=el('div','outbox-row',`${o.state} · ${o.text?.slice(0,100)||'Message'}`);if(['failed','unknown','held','queued'].includes(o.state)){row.append(button('Remove',async()=>{await api(`/api/threads/${scope}/replies/${o.id}`,{action:'remove'});await refreshConversation();}));if(['queued','held','failed'].includes(o.state))row.append(button('Edit',async()=>{const text=prompt('Edit failed message',o.text);if(text){await api(`/api/threads/${scope}/replies/${o.id}`,{action:'edit',text});await refreshConversation();}}));if(['queued','held'].includes(o.state))row.append(button('Send now',async()=>{await api(`/api/threads/${scope}/replies/${o.id}`,{action:'send'});await refreshConversation();}));if(['failed','unknown'].includes(o.state))row.append(button('Retry',async()=>{if(o.state==='unknown'&&!confirm('Delivery is uncertain. Retrying can duplicate work. Check the conversation first. Retry anyway?'))return;await api(`/api/threads/${scope}/replies/${o.id}`,{action:'retry',confirmUnknown:o.state==='unknown'});await refreshConversation();}));}holder.append(row);}}
async function ackVisible(){if(document.visibilityState!=='visible'||!authenticated||document.querySelector('dialog[open]')||$('main').getBoundingClientRect().width===0)return;const data=cache.get(scope),root=$('transcript').getBoundingClientRect();if(!data)return;const rendered=[...$('transcript').children];const visible=n=>{const r=n.getBoundingClientRect();return r.top>=root.top&&r.bottom<=root.bottom;};if(scope==='coordinator'){for(const t of data.turns||[]){const n=rendered.find(n=>n.dataset.id===t.id+'/response'),key=deviceId+':'+t.id;if(t.state==='completed'&&n&&visible(n)&&!receipts.has(key)){receipts.add(key);api(`/api/voice/turns/${t.id}/presented`,{}).catch(()=>receipts.delete(key));}}}else if(data.catchup?.token&&!$('important').checked&&rendered.length&&rendered.every(visible)){const key=data.catchup.token;if(!receipts.has(key)){receipts.add(key);api(`/api/threads/${scope}/catchup/read`,{token:key}).catch(()=>receipts.delete(key));}}}
function saveView(){if(!openedScope)return;const list=$('transcript'),first=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top);readerViews.set(openedScope,{follow,important:$('important').checked,note:activeNote,anchor:first?.dataset.id,offset:first?first.getBoundingClientRect().top-list.getBoundingClientRect().top:0,top:list.scrollTop});}
function restoreView(view){if(!view||view.follow)return;const list=$('transcript'),anchor=[...list.children].find(n=>n.dataset.id===view.anchor);if(anchor)list.scrollTop+=anchor.getBoundingClientRect().top-list.getBoundingClientRect().top-view.offset;else list.scrollTop=view.top;}
async function open(id){
 saveDraft();saveView();for(const panel of document.querySelectorAll(':popover-open'))panel.hidePopover();scope=id;readFeedback.select(scope);write('scope',scope);const view=readerViews.get(id);follow=view?.follow??true;$('important').checked=view?.important??false;activeNote=view?.note??null;filterPosition=null;
 current=id==='coordinator'?null:cache.get(id)?.snapshot||null;$('draft').value=read(draftKey(),'');openedScope=scope;$('requests').dataset.signature='';$('error').hidden=true;$('transcript').replaceChildren();$('attention-panel').hidden=true;$('reports-panel').hidden=true;document.body.classList.add('chat-open');renderHeader();renderList();renderTranscript();renderRequests();renderOutbox();renderNotes();restoreView(view);
 await refreshConversation();if(scope===id){if(follow)$('transcript').scrollTop=$('transcript').scrollHeight;else restoreView(view);}
}
async function refreshConversation(before=null){
 const selected=scope,hasSnapshot=cache.has(selected);const request=lanes.run(selected,!!before,async()=>{
   if(selected==='coordinator'){
    const data=await api('/api/voice/history?conversationOnly=1'+(before?'&before='+encodeURIComponent(before):''));
    const old=cache.get(selected)||{},known=new Map((old.turns||[]).map(t=>[t.id,t]));for(const t of data.turns||[])known.set(t.id,t);
    const turns=[...known.values()].sort((a,b)=>(a.createdAt||a.created_at||0)-(b.createdAt||b.created_at||0));
    cache.set(selected,{...data,turns,...(!before&&old.hasEarlier?{hasEarlier:true,before:old.before}:{})});if(scope===selected)current=null;
   }else{
    const notesVersion=notesVersions.get(selected)||0,metadataTicket=cache.ticket();
    const data=await api(`/api/threads/${encodeURIComponent(selected)}?view=timeline`+(before?'&before='+encodeURIComponent(before):''));
    const old=cache.get(selected)||{};
    if(!before&&notesVersion!==(notesVersions.get(selected)||0))data.notes=old.snapshot?.notes||[];
    const page=cache.head(selected,data,{older:!!before,ticket:metadataTicket});
    if(scope===selected)current=page.snapshot;
   }
   if(scope===selected){renderHeader();renderTranscript();renderRequests();renderOutbox();renderNotes();}
 });
 try{return await (before?request:readFeedback.track(selected,request,{hasContent:hasSnapshot}));}
 catch(error){if(scope===selected)throw error;}
}
const historyDemand=new Map(),historyConsumed=new Map(),earlierReads=new Set();let edgeTimer,lastWheel=0;
function demandOlder(){historyDemand.set(scope,(historyDemand.get(scope)||0)+1);follow=false;scheduleEdge();}
function scheduleEdge(){clearTimeout(edgeTimer);edgeTimer=setTimeout(()=>{if($('transcript').scrollTop<100&&!$('important').checked)void run(loadEarlier)();},150);}
async function loadEarlier(){
 const selected=scope,data=cache.get(selected),demand=historyDemand.get(selected)||0;
 if(!data?.hasEarlier||earlierReads.has(selected)||demand<=(historyConsumed.get(selected)||0)||$('important').checked)return;
 earlierReads.add(selected);historyConsumed.set(selected,demand);
 try{await refreshConversation(data.before);}finally{earlierReads.delete(selected);if(scope===selected&&(historyDemand.get(selected)||0)>demand)scheduleEdge();}
}
const backgroundHeads=new Set(),backgroundFlights=new Set(),backgroundReadAt=new Map();
let backgroundTimer;
function queueBackgroundHead(id){
 if(!id||id==='coordinator'||status?.capabilities?.readOnlyTimeline!==true)return;
 backgroundHeads.delete(id);backgroundHeads.add(id);
 while(backgroundHeads.size>32)backgroundHeads.delete(backgroundHeads.values().next().value);
 if(!backgroundTimer)backgroundTimer=setTimeout(pumpBackgroundHeads,250);
}
function pumpBackgroundHeads(){
 backgroundTimer=null;if(!authenticated||document.visibilityState!=='visible')return;
 for(const id of [...backgroundHeads]){
  if(backgroundFlights.size>=2)break;
  if(backgroundFlights.has(id)||Date.now()-(backgroundReadAt.get(id)||0)<5000)continue;
  backgroundHeads.delete(id);backgroundFlights.add(id);backgroundReadAt.set(id,Date.now());
  void lanes.run(id,false,async()=>{
   const ticket=cache.ticket(),notesVersion=notesVersions.get(id)||0;
   const data=await api('/api/threads/'+encodeURIComponent(id)+'/head?view=timeline');
   if(notesVersion!==(notesVersions.get(id)||0))data.notes=cache.get(id)?.snapshot?.notes||[];
   const page=cache.head(id,data,{ticket});
   if(scope===id){current=page.snapshot;renderHeader();renderTranscript();renderRequests();renderOutbox();renderNotes();}
  }).catch(()=>{}).finally(()=>{backgroundFlights.delete(id);if(backgroundHeads.size&&!backgroundTimer)backgroundTimer=setTimeout(pumpBackgroundHeads,500);});
 }
 if(backgroundHeads.size&&!backgroundTimer)backgroundTimer=setTimeout(pumpBackgroundHeads,1000);
}
async function refreshList(){const metadataTicket=cache.ticket();const data=await api('/api/threads'+($('filter').value==='archived'?'?archived=true':''));threads=cache.catalog(data.threads||[],metadataTicket);$('catalog-note').textContent=data.catalogPartial?'Showing a bounded inventory. Search or open known work.':'';renderList();for(const id of cache.catalogChanges){if(id===scope)scheduleRefresh({head:true});else queueBackgroundHead(id);}}
async function send(){const text=$('draft').value.trim();if(!text)return;if(deliveryFlight)await deliveryFlight;if(pending)throw Error('Check the saved message delivery before sending another.');saveDraft();const message={...pendingFor(scope,text,$('send-mode').value),deviceId};persistPending(message);await deliverPending();}
function clearDelivered(message){if(message.scope===scope&&$('draft').value.trim()===message.text){$('draft').value='';saveDraft();}else if(read(draftKey(message.scope),'').trim()===message.text)write(draftKey(message.scope),'');persistPending(null);}
async function deliverPending(){if(deliveryFlight)return deliveryFlight;deliveryFlight=performPending();try{return await deliveryFlight;}finally{deliveryFlight=null;renderHeader();}}
async function performPending(){const p=pending;if(!p||p.deviceId!==deviceId)throw Error('Saved delivery belongs to another paired device.');$('send').disabled=true;try{if(p.scope==='coordinator'){await api('/api/voice/start',{threadId:null});await api('/api/voice/text',{turnId:p.id,text:p.text});}else await api(`/api/threads/${p.scope}/reply`,{id:p.id,text:p.text,mode:p.mode});clearDelivered(p);await refreshConversation();await refreshList();}catch(e){if(e.status&&e.status<500){persistPending(null);}throw e;}finally{renderHeader();}}
async function reconcilePending(){if(deliveryFlight)return deliveryFlight;if(!pending)return;const p=pending;if(p.deviceId!==deviceId)throw Error('This saved send belongs to another browser pairing.');const path=p.scope==='coordinator'?`/api/voice/turns/${p.id}`:`/api/replies/${p.id}`;try{await api(path);clearDelivered(p);await refreshConversation();}catch(e){if(e.status!==404)throw e;if(pending?.id===p.id)await deliverPending();}}
let runtimeConnected=null;
function handleEvent(m){
 if(immersion.handle(m))return;
 if(m.type==='rateLimits'){status={...status,usage:m.usage};renderUsage();return;}
 if(m.type==='status'){
  const recovered=runtimeConnected===false&&m.connected===true;runtimeConnected=m.connected;
  setLabel($('connection'),m.connected===false?'Codex reconnecting…':'Connected');
  if(recovered)scheduleRefresh({head:true,catalog:true,attention:true});return;
 }
 const threadId=m.threadId||m.notification?.thread_id||m.event?.params?.threadId;
 if(m.type==='timeline'){
  const known=cache.timeline(threadId,m.row);
  if(threadId===scope){if(known){renderTranscript();if(m.row.kind==='request')scheduleRefresh({head:true,attention:true});}else scheduleRefresh({head:true});}
  else if(scope==='coordinator'&&cache.get(scope)?.turns?.some(t=>!['completed','failed','cancelled'].includes(t.state)))scheduleRefresh({head:true});
  else if(!known&&cache.has(threadId))queueBackgroundHead(threadId);
  return;
 }
 if(['threadPreview','sessionPreview','sessionActivity','sessionInteraction','threadRenamed','sessionStarted'].includes(m.type)){
  if(!threadId){scheduleRefresh({catalog:true});return;}
  const patch=m.thread||Object.fromEntries(Object.entries(m).filter(([key])=>['name','preview','previewRole','previewKind','activityAt','recencyAt','status','updatedAt','unreadCount','interactionId'].includes(key)));
  const changed=cache.summary(threadId,patch);threads=cache.list();renderList();
  if(threadId===scope){if(patch.name!==undefined||patch.status!==undefined)renderHeader();if(changed&&(['assistant','user'].includes(patch.previewRole)||m.type==='sessionInteraction'))scheduleRefresh({head:true});}
  else if(changed&&(['assistant','user'].includes(patch.previewRole)||m.type==='sessionInteraction'))queueBackgroundHead(threadId);
  return;
 }
 if(m.type==='contextNotes'){if(!Array.isArray(m.notes))return;notesVersions.set(threadId,(notesVersions.get(threadId)||0)+1);const cached=cache.get(threadId);if(cached?.snapshot)cached.snapshot.notes=m.notes;if(threadId===scope&&current){current.notes=m.notes;renderTranscript();}return;}
 if(m.type==='notification'){
  if(document.visibilityState==='hidden'&&'Notification' in window&&Notification.permission==='granted'&&!notifySeen.has(m.notification.id)){
   notifySeen.add(m.notification.id);const n=new Notification('NextComp update',{body:'Work has an update. Open NextComp to review it.',tag:'nextcomp-'+threadId});
   n.onclick=()=>{window.focus();if(threadId)void run(()=>open(threadId))();n.close();};
  }
  scheduleRefresh({head:threadId===scope,attention:true,catalog:true});return;
 }
 if(['attention','attentionResolved','request'].includes(m.type)){scheduleRefresh({head:threadId===scope,attention:true});return;}
 if(['reply','turnRecovery','message'].includes(m.type)){scheduleRefresh({head:threadId===scope,catalog:true});return;}
 if(m.type==='codex'){
  if(threadId===scope&&['turn/started','turn/completed','item/started','item/completed','thread/status/changed'].includes(m.event?.method))scheduleRefresh({head:true});
  return;
 }
 // Unknown and aggregate activity events do not invalidate a conversation.
 // The independent periodic reconciliation remains the compatibility fallback.
}
function connectEvents(){
 clearTimeout(reconnectTimer);if(!authenticated)return;ws?.close();
 ws=new WebSocket(clientURL(base,'events').replace(/^http/,'ws'));const own=ws;
 ws.onopen=()=>{$('connection').textContent='Connected';void run(async()=>{await Promise.all([refreshList(),refreshConversation(),refreshAttention()]);await reconcilePending();})()};
 ws.onmessage=e=>{let m;try{m=JSON.parse(e.data);}catch{return;}handleEvent(m);};
 ws.onclose=()=>{if(ws!==own||!authenticated)return;$('connection').textContent='Reconnecting…';reconnectTimer=setTimeout(connectEvents,3000);};ws.onerror=()=>own.close();
}
let refreshTimer,lastHeadRefresh=0;const requestedHeads=new Set();let requestedCatalog=false,requestedAttention=false;
function scheduleRefresh({head=true,catalog=false,attention=false}={}){
 if(head)requestedHeads.add(scope);requestedCatalog||=catalog;requestedAttention||=attention;
 if(refreshTimer)return;
 refreshTimer=setTimeout(()=>{
  refreshTimer=null;const refreshHead=requestedHeads.has(scope),catalog=requestedCatalog,attention=requestedAttention;
  requestedHeads.clear();requestedCatalog=requestedAttention=false;
  if(authenticated&&document.visibilityState==='visible'){
   if(refreshHead)lastHeadRefresh=performance.now();
   void run(()=>Promise.all([...(refreshHead?[refreshConversation()]:[]),...(catalog?[refreshList()]:[]),...(attention?[refreshAttention()]:[])]))();
  }
 },Math.max(120,head?(scope==='coordinator'?1000:500)-(performance.now()-lastHeadRefresh):120));
}
async function refreshAttention(){const data=await api('/api/attention');$('attention-count').textContent=data.notifications.length;$('reports-toggle').dataset.count=data.notifications.length||'';$('attention-panel').replaceChildren(el('h2','','Needs your attention'));for(const n of data.notifications){const box=el('div','inbox-item');box.append(el('p','',n.title),el('p','',n.body),button('Open original work',()=>open(n.thread_id)));$('attention-panel').append(box);}}
async function showReports(before=null){const data=await api('/api/coordinator/reports'+(before?'?before='+before:''));if(!before)$('reports-panel').replaceChildren(el('h2','','Work updates'));for(const r of data.reports||data.items||[]){const box=el('div','inbox-item');box.append(el('p','',r.response||r.text||''),button('Mark this report read',async()=>{await api(`/api/voice/turns/${r.id}/presented`,{});}));for(const a of r.actions||[])if(a.threadId)box.append(button('Open work',()=>open(a.threadId)));$('reports-panel').append(box);}if($('reports-panel').children.length===1)$('reports-panel').append(el('p','muted','No scheduled reports yet. Enable scheduling in Settings for this browser.'));if(data.hasEarlier)$('reports-panel').append(button('Older reports',()=>showReports(data.before)));if(!before)$('reports-panel').hidden=false;}
function readThemePreference(){try{return JSON.parse(localStorage.getItem('nextcomp.web.theme'));}catch{return null;}}
async function boot(){try{try{status=await api('/api/status');}catch(e){if(e.status!==401)throw e;try{await api('/web/tailnet-login',{});}catch(loginError){if([403,404].includes(loginError.status))throw e;throw loginError;}status=await api('/api/status');}deviceId=status.deviceId;voice.profile=deviceId;voice.legacyProfile=read('deviceId')||deviceId;if(await importPreviousProfile(base,deviceId)){scope=read('scope','coordinator');pending=read('pending',null);newPending=read('newPending',null);const preference=readThemePreference();if(preference)window.NextCompTheme?.select(preference);}authenticated=true;$('startup').hidden=true;$('pairing').hidden=true;$('app').hidden=false;$('host-info').textContent=`${status.host} · ${status.version} · ${status.device}`;if(read('deviceId')&&read('deviceId')!==deviceId){const original=read('deviceId');if(pending)write('detachedPending.'+original,pending);if(newPending)write('detachedStart.'+original,newPending);pending=null;newPending=null;write('pending',null);write('newPending',null);scope='coordinator';}write('deviceId',deviceId);$('cwd').value=status.defaultCwd||'';renderUsage();connectEvents();await Promise.allSettled([refreshList(),open(scope),refreshAttention()]);await voice.restore();void voice.loadCapabilities();if(pending)await reconcilePending();if(newPending)await reconcileStart();}catch(e){if(e.status===401){$('startup').hidden=true;$('pairing').hidden=false;$('app').hidden=true;}else error(e);}}
$('pair-form').onsubmit=async e=>{e.preventDefault();const b=$('pair-form').querySelector('button');b.disabled=true;try{await api('/web/pair',Object.fromEntries(new FormData(e.target)));await boot();}catch(err){$('pair-error').textContent=err.message;}finally{b.disabled=false;}};
$('composer').onsubmit=run(async e=>{e.preventDefault();await send();});$('draft').oninput=saveDraft;$('draft').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();void run(send)();}};
$('coordinator').onclick=run(()=>open('coordinator'));$('refresh').onclick=run(async()=>{seatOrder=[];await refreshList();});$('search').oninput=renderList;$('filter').onchange=run(async()=>{seatOrder=[];await refreshList();});$('back').onclick=()=>{saveDraft();saveView();document.body.classList.remove('chat-open');seatOrder=[];renderList();};$('important').onchange=()=>toggleImportant();$('earlier').onclick=run(async()=>{demandOlder();await loadEarlier();});$('latest').onclick=()=>{follow=true;$('transcript').scrollTop=$('transcript').scrollHeight;};$('transcript').onscroll=()=>{follow=$('transcript').scrollHeight-$('transcript').scrollTop-$('transcript').clientHeight<90;void ackVisible();scheduleEdge();};
$('attention-toggle').onclick=run(async()=>{await refreshAttention();$('attention-panel').hidden=!$('attention-panel').hidden;document.body.classList.add('chat-open');});$('reports-toggle').onclick=run(()=>showReports());$('settings').onclick=run(async()=>{$('settings-dialog').showModal();renderUsage();const prefs=await api('/api/coordinator/reporting');$('report-enabled').checked=prefs.enabled;$('report-interval').value=prefs.intervalMinutes;$('report-stale').value=prefs.staleAfterMinutes;});$('save-reporting').onclick=run(async()=>{await api('/api/coordinator/reporting',{enabled:$('report-enabled').checked,intervalMinutes:Number($('report-interval').value),staleAfterMinutes:Number($('report-stale').value)});$('settings-dialog').hidePopover?.();$('settings-dialog').close();});$('notifications').onclick=run(async()=>{if(!('Notification' in window))throw Error('Browser notifications are unavailable.');const permission=await Notification.requestPermission();$('notifications').textContent=permission==='granted'?'Notifications enabled while connected':`Notifications ${permission}`;});
$('logout').onclick=run(async()=>{saveDraft();await voice.close();await api('/web/logout',{});authenticated=false;ws?.close();clear();location.reload();});$('mic').onclick=run(()=>voice.capture(scope));$('voice-send').onclick=run(async()=>{await voice.send(scope);await refreshConversation();});$('voice-clear').onclick=run(()=>voice.discard());
$('new-task').onclick=run(async()=>{if(newPending){await reconcileStart();return;}$('new-prompt').value=read('newDraft','');$('new-dialog').showModal();const data=await api('/api/projects');$('projects').replaceChildren(...data.projects.map(p=>{const n=el('option');n.value=p.cwd;return n;}));});$('new-prompt').oninput=()=>write('newDraft',$('new-prompt').value);$('cancel-new').onclick=()=>$('new-dialog').close();
async function reconcileStart(){if(startFlight)return startFlight;startFlight=performStart();try{return await startFlight;}finally{startFlight=null;}}
async function performStart(){if(!newPending)return;const p=newPending;let result;try{result=await api(`/api/session-starts/${p.id}`);}catch(e){if(e.status!==404)throw e;try{result=await api('/api/threads',p);}catch(e){if(e.status&&e.status<500){newPending=null;write('newPending',null);}throw e;}}newPending={...p,state:result.state};write('newPending',newPending);renderOutbox();if(result.state==='started'){newPending=null;write('newPending',null);write('newDraft','');$('new-dialog').close();await refreshList();await open(result.thread_id);}else if(['failed','unknown'].includes(result.state)){if(result.state==='failed'){newPending=null;write('newPending',null);}throw Error(result.error||'Task creation uncertain. Check recent work before starting again.');}else $('new-error').textContent='Task accepted. Waiting for the backend to create it…';}
$('new-form').onsubmit=async e=>{e.preventDefault();try{if(!newPending){newPending={id:crypto.randomUUID(),cwd:$('cwd').value,prompt:$('new-prompt').value,permissions:$('permissions').value};write('newPending',newPending);}await reconcileStart();}catch(e){$('new-error').textContent=e.message;}};
$('session-menu').onclick=run(async()=>{$('controls-dialog').showModal();$('watch').textContent=current?.watched?'Unfollow updates':'Follow updates';$('archive').textContent=$('filter').value==='archived'?'Restore':'Archive';const data=await api('/api/models');models=data.models;$('model').replaceChildren(...models.map(m=>{const o=el('option','',m.name);o.value=m.model;return o;}));if(current?.turnSettings?.model)$('model').value=current.turnSettings.model;setEfforts();});
function setEfforts(){const model=models.find(m=>m.model===$('model').value);$('effort').replaceChildren(...(model?.efforts||[]).map(e=>{const o=el('option','',e.reasoningEffort);o.value=e.reasoningEffort;return o;}));$('effort').value=current?.turnSettings?.effort||model?.defaultEffort;}$('model').onchange=setEfforts;
for(const [id,action,body] of [['watch','watch',()=>({enabled:!current?.watched})],['stop','interrupt',()=>({})],['resume','queue/resume',()=>({})],['archive','archive',()=>({})]])$(id).onclick=run(async()=>{await api(`/api/threads/${scope}/${id==='archive'&&$('filter').value==='archived'?'unarchive':action}`,body());$('controls-dialog').hidePopover?.();$('controls-dialog').close();await refreshConversation();await refreshList();});
$('rename').onclick=run(async()=>{const name=prompt('Session name',current?.thread?.name||'');if(name){await api(`/api/threads/${scope}/rename`,{name});await refreshConversation();await refreshList();}});$('save-turn').onclick=run(async()=>{await api(`/api/threads/${scope}/settings`,{model:$('model').value,effort:$('effort').value,mode:$('turn-mode').value==='plan'?'plan':'default'});$('controls-dialog').hidePopover?.();$('controls-dialog').close();await refreshConversation();});
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installEvent=e;$('install').hidden=false;});$('install').onclick=run(async()=>{await installEvent?.prompt();installEvent=null;$('install').hidden=true;});
window.addEventListener('online',()=>{if(authenticated){connectEvents();scheduleRefresh();}});window.addEventListener('offline',()=>{$('connection').textContent='Offline · drafts saved';});document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&authenticated){scheduleRefresh();if(ws?.readyState!==WebSocket.OPEN)connectEvents();}});window.addEventListener('pagehide',()=>{saveDraft();void voice.close();});window.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='k'){e.preventDefault();$('search-panel').hidden=false;$('search').focus();}if(e.key==='Escape'&&!document.querySelector('dialog[open],:popover-open')&&innerWidth<=760)document.body.classList.remove('chat-open');});
setInterval(()=>{if(!authenticated||polling||document.visibilityState!=='visible')return;polling=true;void run(async()=>{await Promise.all([refreshConversation(),refreshList(),refreshAttention()]);if(newPending)await reconcileStart();})().finally(()=>polling=false);},10000);
if('serviceWorker' in navigator)navigator.serviceWorker.register(clientURL(base,'sw.js'),{scope:base.pathname}).catch(()=>{});
const immersion=new ImmersionLayer(api,base,text=>{$('immersion-state').textContent=text;$('immersion-enabled').checked=immersion.enabled;});
$('immersion-enabled').onchange=run(()=>immersion.setEnabled($('immersion-enabled').checked));
void boot().then(()=>{if(authenticated)void immersion.load();});

function renderUsage(){const usage=usageSummary(status?.usage);setLabel($('usage'),usage.compact);$('usage').title=usage.reset+(usage.stale?' · Last known':'');const box=$('account-usage');box.replaceChildren(el('strong','',usage.compact),el('p','',usage.reset),el('p','',usage.credits),el('p','muted',usage.continuation),el('p','muted',usage.stale?'Last known observation':'Current account observation'));}
$('usage').onclick=()=>{$('settings-dialog').showModal();renderUsage();};
let activeNote=null,filterPosition=null;
function toggleImportant(note=null){
 const list=$('transcript');if($('important').checked&&!filterPosition){const first=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top);filterPosition={scope,id:first?.dataset.id,offset:first?first.getBoundingClientRect().top-list.getBoundingClientRect().top:0,top:list.scrollTop,follow};}
 activeNote=$('important').checked?note:null;follow=false;renderTranscript();renderNotes();
 if(!$('important').checked&&filterPosition?.scope===scope){const saved=filterPosition,n=[...list.children].find(n=>n.dataset.id===saved.id);if(n)list.scrollTop+=n.getBoundingClientRect().top-list.getBoundingClientRect().top-saved.offset;else list.scrollTop=saved.top;follow=saved.follow;filterPosition=null;}
}
const removingNotes=new Set(),notesVersions=new Map();
function renderNotes(){
 const notes=current?.notes||[],label=$('answers-label');
 label.textContent=notes.length?'Answers '+notes.length:'Answers';
 $('answers-filter').hidden=scope==='coordinator';
 for(const old of $('transcript').querySelectorAll('.saved-answer-actions'))old.remove();
 if(!$('important').checked)return;
 for(const note of notes){
  const row=[...$('transcript').children].find(n=>n._row&&(n._row.id===note.id||n._row.itemId===note.id||n._row.id.endsWith('/'+note.id)));
  if(!row)continue;
  const selected=scope,key=selected+'/'+note.id,actions=el('div','message-actions saved-answer-actions');
  const remove=button(removingNotes.has(key)?'Removing…':'Dismiss saved answer',async()=>{
   if(removingNotes.has(key))return;
   removingNotes.add(key);renderNotes();
   try{
    const result=await api('/api/threads/'+encodeURIComponent(selected)+'/notes/remove',{id:note.id});
    if(!Array.isArray(result.notes))throw Error('Saved answer removal was not confirmed.');
    notesVersions.set(selected,(notesVersions.get(selected)||0)+1);
    const cached=cache.get(selected);if(cached?.snapshot)cached.snapshot.notes=result.notes;
    if(scope===selected){current.notes=result.notes;if(activeNote===note.id)activeNote=null;renderTranscript();}
   }catch(e){if(scope===selected)error(e);}
   finally{removingNotes.delete(key);if(scope===selected)renderNotes();}
  });
  remove.disabled=removingNotes.has(key);actions.append(remove);row.append(actions);
 }
}
$('collapse-runs').onclick=()=>{for(const run of document.querySelectorAll('.run-block[open]'))run.open=false;$('collapse-runs').hidden=true;};
function page(direction){follow=false;const list=$('transcript');list.scrollBy({top:direction*list.clientHeight,behavior:'auto'});if(direction<0){demandOlder();if(list.scrollTop<100)void run(loadEarlier)();}}
$('page-up').onclick=()=>page(-1);$('page-down').onclick=()=>page(1);
$('toggle-keyboard').onclick=()=>{$('composer').classList.toggle('keyboard-open');if($('composer').classList.contains('keyboard-open'))$('draft').focus();};
let playbackVolume=read('playbackVolume',1);function volume(){voice.volume=playbackVolume;if(voice.audio)voice.audio.volume=playbackVolume;$('playback-volume').textContent=playbackVolume===0?'Muted':`${playbackVolume*100}%`;}$('playback-volume').onclick=()=>{playbackVolume=playbackVolume===0?.5:playbackVolume===.5?1:0;write('playbackVolume',playbackVolume);volume();};volume();
// One bottom control surface; reading space is not consumed by a second header.
const toolbar=document.querySelector('.transcript-toolbar'),header=document.querySelector('.conversation-header');
toolbar.prepend($('back'));toolbar.append($('parent'),$('session-menu'));for(const control of toolbar.querySelectorAll('button'))control.type='button';$('composer').append(toolbar);header.classList.add('footer-identity');$('composer').prepend(header);
$('draft').addEventListener('focus',()=>{$('composer').classList.add('keyboard-open');});

$('work-list').addEventListener('scroll',()=>{listReadingUntil=Date.now()+800;},{passive:true});

window.addEventListener('keydown',e=>{if(e.code==='F8'&&!e.repeat&&!document.querySelector('dialog[open],:popover-open')){e.preventDefault();void run(()=>voice.capture(scope))();}});

const inputRow=el('div','input-row');inputRow.append($('toggle-keyboard'),$('mic'),$('draft'),$('send'));const composer=$('composer');composer.insertBefore(inputRow,document.querySelector('.composer-actions'));
const deliveryLabel=el('label','','Next message');deliveryLabel.append($('send-mode'));$('controls-dialog').append(deliveryLabel);
function anchoredControls(){const panel=$('controls-dialog'),anchor=$('session-menu').getBoundingClientRect();panel.popover='auto';panel.classList.add('anchored-panel');panel.style.left=Math.max(12,Math.min(innerWidth-340,anchor.right-320))+'px';panel.style.bottom=(innerHeight-anchor.top+8)+'px';panel.style.top='auto';panel.style.margin='0';panel.showPopover();}
$('session-menu').onclick=run(async()=>{const data=await api('/api/models');models=data.models||[];$('model').replaceChildren(...models.map(m=>{const option=el('option','',m.name||m.model);option.value=m.model;return option;}));$('model').value=current?.turnSettings?.model||models[0]?.model||'';setEfforts();$('watch').textContent=current?.watched?'Unfollow updates':'Follow updates';anchoredControls();});
document.querySelector('#controls-dialog .close').onclick=e=>{e.preventDefault();$('controls-dialog').hidePopover();};

$('transcript').addEventListener('wheel',event=>{if(event.deltaY<0){const now=performance.now();if(now-lastWheel>220)demandOlder();lastWheel=now;scheduleEdge();}},{passive:true});
let touchY=null,touchDemanded=false;$('transcript').addEventListener('touchstart',event=>{touchY=event.touches[0]?.clientY;touchDemanded=false;},{passive:true});
$('transcript').addEventListener('touchmove',event=>{if(!touchDemanded&&touchY!=null&&(event.touches[0]?.clientY||0)-touchY>3){touchDemanded=true;demandOlder();}scheduleEdge();},{passive:true});
$('transcript').addEventListener('keydown',event=>{if(['PageUp','ArrowUp','Home'].includes(event.key))demandOlder();});
function samePresentation(a,b){
 if(a===b)return true;if(!a||a.kind!==b.kind)return false;
 if(a.kind==='runs')return a.rows.length===b.rows.length&&a.rows.every((r,i)=>samePresentation(r,b.rows[i]));
 if(!['title','text','detail','status','phase','type'].every(key=>a[key]===b[key]))return false;
 const equalList=(left=[],right=[],keys)=>left.length===right.length&&left.every((item,i)=>keys.every(key=>item[key]===right[i][key]));
 return equalList(a.attachments,b.attachments,['name','url','mime'])&&equalList(a.actions,b.actions,['threadId','name','type']);
}

// Catalog actions share the same bottom reach zone as the Android catalog.
const sidebar=$('sidebar'),catalogDock=el('div','catalog-dock');catalogDock.id='catalog-dock';
const find=button('Find',()=>{const panel=$('search-panel');panel.hidden=!panel.hidden;if(!panel.hidden)$('search').focus();});find.id='find-work';
const searchPanel=el('div','search-panel');searchPanel.id='search-panel';searchPanel.hidden=true;searchPanel.append(document.querySelector('.search'),document.querySelector('.list-tools'));
const usageNode=$('usage'),usageValue=el('span','usage-value',usageNode.textContent);usageValue.id='usage';usageNode.replaceWith(usageValue);$('settings').append(usageValue);usageValue.onclick=null;
function dockIcon(control,name,label){control.type='button';control.setAttribute('aria-label',label);control.title=label;const preserved=control.id==='settings'?usageValue:null;control.replaceChildren();control.append(animatedSymbol(name,{size:32}));if(preserved)control.append(preserved);}
dockIcon($('coordinator'),'nextcomp','Coordinator');dockIcon($('new-task'),'editnote','New task');dockIcon(find,'search','Find work');dockIcon($('reports-toggle'),'inbox','Work updates');dockIcon($('settings'),'computer','Settings and account usage');
catalogDock.append($('coordinator'),$('new-task'),find,$('reports-toggle'),$('settings'));
const oldFooter=sidebar.querySelector('footer');oldFooter.replaceChildren(catalogDock,$('connection'));sidebar.append(searchPanel,oldFooter);document.querySelector('.brand').hidden=true;document.querySelector('.work-heading').hidden=true;document.querySelector('.nav-row').hidden=true;
const inbox=el('section','work-inbox');inbox.id='work-inbox';inbox.popover='auto';inbox.append($('attention-toggle'),$('attention-panel'),$('reports-panel'),button('Close',()=>inbox.hidePopover()));document.body.append(inbox);
function anchorPanel(panel,control){panel.popover='auto';panel.classList.add('anchored-panel');const rect=control.getBoundingClientRect();panel.style.left=Math.max(12,Math.min(innerWidth-340,rect.right-320))+'px';panel.style.bottom=(innerHeight-rect.top+8)+'px';panel.style.top='auto';panel.style.margin='0';panel.showPopover();}
$('reports-toggle').onclick=run(async()=>{if(inbox.matches(':popover-open')){inbox.hidePopover();return;}await Promise.all([refreshAttention(),showReports()]);$('attention-panel').hidden=false;$('reports-panel').hidden=false;anchorPanel(inbox,$('reports-toggle'));});
$('settings').onclick=run(async()=>{renderUsage();anchorPanel($('settings-dialog'),$('settings'));const prefs=await api('/api/coordinator/reporting');$('report-enabled').checked=prefs.enabled;$('report-interval').value=prefs.intervalMinutes;$('report-stale').value=prefs.staleAfterMinutes;});
document.querySelector('#settings-dialog .close').onclick=e=>{e.preventDefault();$('settings-dialog').hidePopover();};

const transcriptNode=$('transcript'),readerFrame=el('div','transcript-frame');transcriptNode.replaceWith(readerFrame);readerFrame.append(transcriptNode,$('message-loading'));

const startupMark=document.querySelector('#startup>img');if(startupMark)startupMark.replaceWith(animatedSymbol('nextcomp',{size:56}));
