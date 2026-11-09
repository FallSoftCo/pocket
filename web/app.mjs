import {UxMeasurements} from './ux-measurements.mjs';
import {NativeSettingsPanel,addServiceTierControl} from './native-settings.mjs';
import {commandOutputButton,openTerminals,executionApiFor} from './native-execution.mjs';
import {ConversationToolsPane} from './conversation-tools.mjs';
import {messageTime} from './message-time.mjs';
import {roomLead,assertLeadTarget} from './room-lead.mjs';
import {HistoryReader} from './native-history.mjs';
import {roomActorLabel,roomOriginSummary,roomOriginRows,currentRoomActors} from './room-identity.mjs';
import {RequestList} from './request-list.mjs';
import {InlineMedia} from './media-previews.mjs';
import {ReviewPane} from './review-pane.mjs';
import {environmentPath,environmentOf,catalogVisible,catalogTitle} from './environments.mjs';
import {SessionStore} from './session-store.mjs';
import {animatedSymbol,setSymbolMotionReady} from './symbol-motion.mjs';
import {ReadFeedback} from './read-feedback.mjs';
import {ImmersionLayer} from './immersion.mjs';
import {richText,foldRuns,usageSummary,sessionState,canonicalPage,ReadLanes} from './presentation.mjs';
import {sessionIdentity,workTime,compareWork,mergeRows,pendingFor} from './model.mjs';
import {purgeRetiredStorage,retiredValue} from './retirement.mjs';
import {voiceJournal} from './voice-journal.mjs';
import {read,write,clear} from './storage.mjs';
import {BrowserVoice} from './voice.mjs';
import {clientBase,clientURL} from './paths.mjs';
import {importPreviousProfile} from './migration.mjs';
import {RoomClient,isRoom,sourcePath,canAnswer} from './room-client.mjs';
import {roomState,RoomHistory} from './room-model.mjs';
import {deliveryAccepted,bindingMatches,jsonEqual} from './receipt-policy.mjs';
import {AccountUsageCache} from './account-usage.mjs';
import {panelFrame,anchoredPanelPlacement} from './anchored-panel.mjs';
const base=clientBase(import.meta.url);
const ux=new UxMeasurements();
let uxOpen=0,uxFresh=0,uxSend=0,uxFrame=0;
let queueNavigation=0,queueAuthority=0,queueVerifiedNavigation=-1;const queueActions=new Map();
function measureContentLayout(){
 const token=uxOpen;if(!ux.active(token)||uxFrame===token)return;uxFrame=token;
 requestAnimationFrame(()=>{
  if(uxFrame===token)uxFrame=0;
  if(uxOpen!==token||!ux.active(token)||document.visibilityState!=='visible'||document.querySelector('dialog[open]'))return;
  const viewport=$('transcript').getBoundingClientRect();
  if(viewport.width<=0||viewport.height<=0)return;
  const visible=[...$('transcript').querySelectorAll('article.message')].some(node=>{const box=node.getBoundingClientRect();return box.height>0&&box.bottom>viewport.top&&box.top<viewport.bottom&&box.right>viewport.left&&box.left<viewport.right;});
  if(visible)ux.layout(token,uxFresh===token);
 });
}

setSymbolMotionReady(false);
function setLabel(node,text){const source=String(text??'');if(node.dataset.labelSource!==source){node.dataset.labelSource=source;node.textContent=source;if(node.tagName==='BUTTON')node.setAttribute('aria-label',source);}}
const $=id=>document.getElementById(id),el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)setLabel(n,text);return n;};
let scope=read('scope','coordinator'),deviceId='',threads=[],current=null,status=null,ws=null,authenticated=false,polling=false,installEvent=null,models=[],loadSerial=0,earlierBusy=false,reconnectTimer,follow=true;
// Preserve saved room drafts/receipts; a retired selection never becomes a stock ID.
if(isRoom(scope)){write('retiredRoomScope',scope);scope='coordinator';write('scope',scope);}
let openedScope=null,openedDraftKey=null,catalogSettled=false,catalogFailure=false,catalogChecksIncomplete=false;const readerViews=new Map();
const readerKey=(id=scope)=>id+(isRoom(id)?'/'+roomView:'');
let seatOrder=[],deliveryFlight=null,startFlight=null,review=null;let attentionItems=[];
const lanes=new ReadLanes();
const readFeedback=new ReadFeedback(state=>{const node=$('message-loading');node.hidden=!state.visible;if(state.visible)setLabel(node,state.initial?'Loading latest messages…':'Updating messages…');$('transcript').setAttribute('aria-busy',String(state.visible));});
readFeedback.select(scope);

const historyReader=new HistoryReader(async(...args)=>{
 const token=ux.begin('history_request');
 try{const data=await api(...args);ux.mark(token,'history_response');ux.end(token,'complete');return data;}
 catch(error){ux.end(token,error?.name==='AbortError'?'cancelled':'failed');throw error;}
});
const rooms=new RoomClient((...args)=>api(...args));
const accountUsage=new AccountUsageCache(),accountFlights=new Map();
let roomEntries=[],roomView='all';const roomTargets=new Map();
const targetFor=id=>{if(!roomTargets.has(id))roomTargets.set(id,read('roomTarget.'+deviceId+'.'+id,{recipientId:null,audience:'room',sourceEventId:null}));return roomTargets.get(id);};
const cache=new SessionStore(),receipts=new Set(),notifySeen=new Set();cache.protect(scope);
cache.subscribeMetadata(id=>{if(id===scope)renderHeader();});let pending=read('pending',null),newPending=read('newPending',null);
if(pending?.room){write('retiredRoomPending.'+pending.deviceId+'.'+pending.id,pending);pending=null;write('pending',null);}
async function api(path,body,options={}){
 const outgoingThread=/^\/api\/threads\/([^/?]+)\/(?:reply|replies\/[^/?]+|queue\/resume)$/.exec(path)?.[1]||(path.startsWith('/api/replies/')?options.scope:null);
 path=environmentPath(path,options.scope);
 const method=options.method||(body===undefined?'GET':'POST'),binary=body instanceof Blob||body instanceof ArrayBuffer;
 const r=await fetch(clientURL(base,path),{method,credentials:'same-origin',headers:{'Content-Type':binary?'audio/wav':'application/json',...(options.headers||{})},...(body===undefined?{}:{body:binary?body:JSON.stringify(body)}),priority:options.priority||'high',...(method==='GET'?{signal:AbortSignal.any([AbortSignal.timeout(options.timeout||20000),...(options.signal?[options.signal]:[])])}:{})});
 let data;try{data=await r.json();}catch{data={error:`Backend returned HTTP ${r.status}.`};}
 if(!r.ok){if(r.status===401&&authenticated){authenticated=false;queueAuthority++;queueVerifiedNavigation=-1;ux.foreground(false);ws?.close();$('app').hidden=true;$('pairing').hidden=false;}throw Object.assign(Error(data.error||`Request failed (${r.status}).`),{status:r.status,code:data.code,readAvailability:data.readAvailability});}if(outgoingThread&&data.outbox&&(!options.acceptOutbox||options.acceptOutbox(data)))observeOutbox(outgoingThread,data.outbox,!options.paintOutbox||options.paintOutbox());return data;
}
const error=e=>{if($('startup')){$('startup').hidden=true;if($('app').hidden&&$('pairing').hidden)$('pairing').hidden=false;}$('error').textContent=e.message||String(e);$('error').hidden=false;};
const run=fn=>async(...args)=>{try{$('error').hidden=true;await fn(...args);}catch(e){error(e);}};
const conversationToolsPane=new ConversationToolsPane({api,source:()=>scope,device:()=>deviceId,
 appendDraft:(text,id)=>{if(scope!==id)throw Error('Conversation changed.');$('draft').value=$('draft').value+text;saveDraft(true);},
 applied:async(receipt,id)=>{if(receipt.kind==='fork'&&receipt.thread?.id){await refreshList({invalidate:true});await open(receipt.thread.id);}else{if(receipt.kind==='revert'&&scope===id){cache.replaceHistory(id,receipt.result?.historyHead);const text=receipt.result?.draft||'';$('draft').value=$('draft').value?$('draft').value+'\n\n'+text:text;saveDraft(true);}if(scope===id)await refreshConversation();}}
});
const requestList=new RequestList({holder:$('requests'),create:el,button,api,refresh:refreshConversation});
const voice=new BrowserVoice(api,(text,p)=>{
 const holder=$('voice-status');holder.hidden=!text&&!voice.records.some(r=>!['cancelled','submitted','completed'].includes(r.state));holder.replaceChildren();if(text&&!voice.records.some(r=>r.error===text))holder.append(el('span','',text));
 if(voice.recording)holder.append(button('Cancel recording',()=>voice.discard(voice.recording.id)));
 if(text.includes('speech'))holder.append(button('Stop speech',()=>voice.disconnect()));
 for(const record of voice.records.filter(r=>!['cancelled','submitted','completed'].includes(r.state))){const row=el('div','recording-receipt',`${record.scope===scope?'This conversation':'Another conversation'} · ${record.error||record.state}`);if(!voice.jobs.has(record.id)&&record.state!=='unknown')row.append(button('Send saved recording',()=>voice.sendRecord(record.id)));row.append(button('Cancel',()=>voice.discard(record.id)),button('Forget local copy',()=>{if(confirm('This removes only the local recording. Any submitted work and original server receipt remain. Forget this copy?'))return voice.forget(record.id);}));if(record.scope!==scope)row.append(button('Open original conversation',()=>open(record.scope)));holder.append(row);}
 $('mic').classList.toggle('recording',!!voice.recording);$('mic').textContent=voice.recording?'■ Stop and send':'● Record';$('voice-send').hidden=true;$('voice-clear').hidden=true;
});
function draftKey(s=scope){if(isRoom(s)){if(!rooms.entries.has(s)&&s===openedScope&&openedDraftKey)return openedDraftKey;const r=rooms.route(s,{requireAvailable:false});return 'draft.'+JSON.stringify([deviceId,r.environment,r.binding.bindingId,r.binding.installationId,r.binding.authorityId,r.binding.principalId,rooms.entry(s).roomId,targetFor(s)]);}return `draft.${deviceId}.${s}`;}
function saveDraft(edited=false){if(deviceId&&openedScope===scope){const key=openedDraftKey||draftKey(),text=$('draft').value;if(edited||read(key,'')!==text)write('revision.'+key,read('revision.'+key,0)+1);write(key,text);}}
function persistPending(value){pending=value;write('pending',value);renderOutbox();}
function button(label,action){const b=el('button','',label);b.type='button';b.onclick=run(action);return b;}
function stamp(ms){if(!ms)return 'Recency unknown';const d=Math.max(0,Date.now()-ms);return d<60000?'Just now':d<3600000?`${Math.floor(d/60000)}m ago`:d<86400000?`${Math.floor(d/3600000)}h ago`:new Date(ms).toLocaleDateString();}
let listReadingUntil=0,lastSeatRefresh=0;const expandedParents=new Set();
let needsPreviousView=null;
const needsKinds=new Set(['question','approval','deliveryFailed','deliveryUnknown','needsInput','runtimeError']);
function attentionEvidence(thread){
 const value=thread.attention;
 if(value?.version!==1||typeof value.verified!=='boolean'||!Array.isArray(value.items))return null;
 const ids=new Set();
 for(const item of value.items){
  if(!item||!needsKinds.has(item.kind)||typeof item.id!=='string'||!item.id.trim()||typeof item.label!=='string'||!item.label.trim()||ids.has(item.id))return null;
  ids.add(item.id);
 }
 return value;
}
const needsReasons=t=>attentionEvidence(t)?.items||[];
const needsIncomplete=t=>catalogFailure||t.environmentUnavailable||unavailableEnvironments.has(environmentOf(t.id))||(!environmentOf(t.id)&&runtimeConnected===false)||attentionEvidence(t)?.verified!==true;
async function openAttention(thread){
 thread=threads.find(t=>t.id===thread.id);if(!authenticated||!thread||!needsReasons(thread).length)return;
 const id=thread.id,decisions=needsReasons(thread).some(r=>['question','approval'].includes(r.kind));
 const opening=open(id),navigation=queueNavigation;await opening;
 if(decisions&&scope===id&&queueNavigation===navigation&&current?.pending?.length)review.show(true);
}
async function toggleNeedsYou(){
 const list=$('work-list');review.show(false);document.body.classList.remove('chat-open');
 if($('filter').value==='needs-you'){
  const previous=needsPreviousView||{filter:'all',query:'',scroll:0};needsPreviousView=null;
  $('filter').value=previous.filter;$('search').value=previous.query;renderList();await refreshList({invalidate:true});
  if($('filter').value===previous.filter&&$('search').value===previous.query)list.scrollTop=previous.scroll;
 }else{
  needsPreviousView={filter:$('filter').value,query:$('search').value,scroll:list.scrollTop};
  $('filter').value='needs-you';$('search').value='';document.body.classList.remove('chat-open');renderList();await refreshList({invalidate:true});list.scrollTop=0;
 }
}

function renderList(){
 const query=$('search').value.toLowerCase(),filter=$('filter').value,needsOnly=filter==='needs-you',children=new Map();
 const affected=threads.filter(t=>needsReasons(t).length),incomplete=catalogChecksIncomplete||threads.some(needsIncomplete),needsControl=$('needs-you-toggle');
 needsControl.hidden=!affected.length&&!needsOnly;needsControl.dataset.count=affected.length?String(affected.length):'';needsControl.setAttribute('aria-pressed',String(needsOnly));needsControl.setAttribute('aria-label',needsOnly?'Needs you · Show all work':'Needs you · '+affected.length+' sessions');needsControl.title=needsOnly?'Show all work':'Needs you';
 for(const t of threads){const parent=sessionIdentity(t).parentThreadId;if(parent){if(!children.has(parent))children.set(parent,[]);children.get(parent).push(t);}}
 const roomCards=roomEntries.filter(e=>filter!=='archived'&&filter!=='temporary'&&filter!=='empty').map(e=>({id:e.id,_room:e,name:e.room.name,preview:e.room.purpose,recencyAt:e.room.recencyAt||e.room.created_at,environmentName:e.routes.find(r=>r.environment===e.selected)?.name}));
 const visible=[...threads.filter(t=>needsOnly?needsReasons(t).length:catalogVisible(t,filter,query)),...roomCards.filter(t=>!needsOnly&&(filter!=='active'||roomState(t._room.room).tone!=='idle'))].filter(t=>[t.name,t.preview,t.cwd,t.agentNickname].some(x=>String(x||'').toLowerCase().includes(query)));
 const now=Date.now();if(!seatOrder.length||!needsOnly&&now>listReadingUntil&&now-lastSeatRefresh>5000){
  const prior=new Map(seatOrder.map((id,i)=>[id,i]));
  seatOrder=[...threads,...roomCards].sort((a,b)=>Math.floor(workTime(a)/10000)-Math.floor(workTime(b)/10000)||(prior.get(a.id)??1e6)-(prior.get(b.id)??1e6)||a.id.localeCompare(b.id)).map(t=>t.id);lastSeatRefresh=now;
 }for(const t of [...threads,...roomCards])if(!seatOrder.includes(t.id))seatOrder.push(t.id);
 const known=new Set(visible.map(t=>t.id)),ordered=[...visible].filter(t=>needsOnly||query||!known.has(sessionIdentity(t).parentThreadId)||expandedParents.has(sessionIdentity(t).parentThreadId)).sort((a,b)=>seatOrder.indexOf(a.id)-seatOrder.indexOf(b.id));
 const list=$('work-list'),anchor=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top),anchorOffset=anchor?.getBoundingClientRect().top,wasBottom=list.scrollHeight-list.scrollTop-list.clientHeight<60;
 const old=new Map([...list.children].map(n=>[n.dataset.id,n]));let cursor=list.firstChild;
 for(const t of ordered){let n=old.get(t.id);if(!n){n=el('article','work-card');n.dataset.id=t.id;n.tabIndex=0;n.setAttribute('role','button');n.onclick=run(()=>needsReasons(n._thread).length?openAttention(n._thread):open(n._thread.id));n.onkeydown=e=>{if(e.target===n&&['Enter',' '].includes(e.key)){e.preventDefault();void run(()=>needsReasons(n._thread).length?openAttention(n._thread):open(n._thread.id))();}};n.append(el('div','card-heading'),el('span','preview'),el('span','card-meta'));}
  old.delete(t.id);const identity=sessionIdentity(t),state=t._room?roomState(t._room.room,{available:t._room.routes.some(r=>r.available)}):sessionState(t);n.className='work-card'+(scope===t.id?' selected':'')+(identity.isChild?' child':'');n.dataset.state=state.tone;n.setAttribute('aria-current',scope===t.id?'page':'false');if(t._room)n.dataset.roomId=t._room.roomId;
  n._thread=t;
  if(!n.querySelector('input')){let title=n.querySelector('.card-title');if(!title){title=button('',()=>isRoom(n._thread.id)?open(n._thread.id):renameCard(n._thread,n));title.className='card-title';title.addEventListener('click',e=>e.stopPropagation());const titleRow=el('div','card-title-row');titleRow.append(title);n.children[0].prepend(titleRow);}setLabel(title,t._room?'Room · '+t.name:catalogTitle(t));
   const agents=children.get(t.id)||[];let badge=n.querySelector('.child-badge');if(agents.length){if(!badge){badge=button('',()=>{expandedParents.has(t.id)?expandedParents.delete(t.id):expandedParents.add(t.id);renderList();});badge.className='child-badge';badge.addEventListener('click',e=>e.stopPropagation());n.children[0].append(badge);}setLabel(badge,`${agents.length} agent${agents.length===1?'':'s'}`);}else badge?.remove();}
  let archive=n.querySelector('.card-archive');
  if(!t._room){if(!archive){archive=button('',()=>setSessionArchived(n._thread,!n._thread.archived));archive.className='card-archive';archive.addEventListener('click',e=>e.stopPropagation());n.children[0].append(archive);}setLabel(archive,t.archived?'Restore':'Archive');archive.disabled=archiving.has(t.id);archive.title=t.archived?'Return to current sessions':'Move to Archived; keep history';}else archive?.remove();
  const reasons=needsReasons(t);n.dataset.attentionCount=String(reasons.length);const preview=n.children[1];
  preview.classList.toggle('attention-reason',reasons.length>0);
  if(reasons.length){preview.dataset.kind=reasons[0].kind;setLabel(preview,reasons[0].label+(reasons.length>1?' · +'+(reasons.length-1):'')+(needsIncomplete(t)?' · Last known':'')+(t.preview?' · '+t.preview:''));preview.title=reasons.map(r=>r.label).join(' · ')+(needsIncomplete(t)?' · Check incomplete':'');}
  else{delete preview.dataset.kind;preview.removeAttribute('title');setLabel(preview,t.preview||'Open conversation');}
  if(!n.children[2].children.length)n.children[2].append(el('span','card-state'),el('span','card-age'));setLabel(n.children[2].children[0],state.label+(t.unreadCount?' · '+t.unreadCount+' unread':''));setLabel(n.children[2].children[1],stamp(workTime(t))+(t.environmentName?' · '+t.environmentName:'')+(t.environmentUnavailable?' · Unavailable':''));
  n.style.setProperty('--recency',`hsl(${state.tone==='idle'?275:state.tone==='attention'?38:205} ${Math.max(20,75-Math.log2(1+Math.max(0,now-workTime(t))/3600000)*7)}% 65%)`);
  if(n!==cursor)list.insertBefore(n,cursor);cursor=n.nextSibling;
 }for(const n of old.values())n.remove();
 if(wasBottom&&now>listReadingUntil)list.scrollTop=list.scrollHeight;else if(anchor?.isConnected&&anchorOffset!=null)list.scrollTop+=anchor.getBoundingClientRect().top-anchorOffset;
 if(needsOnly)$('catalog-note').textContent=catalogFailure?'Could not check sessions. Show all work to leave this view.':!catalogSettled?'Checking sessions…':incomplete?'Some session checks are unavailable. Existing action items remain visible.':!ordered.length?'No matching action items in this view. Show all work to leave this view.':'';
 else if(!ordered.length)$('catalog-note').textContent=catalogFailure?'Could not load work. Refresh to retry.':!catalogSettled?'Loading work…':'No work matches this view.';
 else if(incomplete)$('catalog-note').textContent='Some session checks are unavailable.';
 else $('catalog-note').textContent='';

}
const archiving=new Set();
async function setSessionArchived(thread,archived){
 const id=thread.id;
 if(archiving.has(id))return;
 if(archived&&!confirm(`Archive “${catalogTitle(thread)}”?\n\nHide from current sessions, hold queued messages and stop notifications. History and drafts stay available. Restore from Archived. Running turns must be stopped first.`))return;
 archiving.add(id);renderList();
 try{
  const result=await api(`/api/threads/${id}/${archived?'archive':'unarchive'}`,{}, {scope:id});
  if(result.ok!==true)throw Error('Archive update was not acknowledged. Refresh before trying again.');
  cache.summary(id,{archived});threads=cache.list();renderList();
  if(archived&&scope===id)await open('coordinator');
  await refreshList({invalidate:true});
 }finally{archiving.delete(id);renderList();}
}
async function renameCard(thread,card){
 const input=el('input');input.value=thread.name||'';input.setAttribute('aria-label','Session name');input.onclick=e=>e.stopPropagation();card.children[0].replaceChildren(input);input.focus();input.select();let finished=false;
 const finish=async save=>{if(finished)return;finished=true;try{const name=input.value.trim();if(save&&name&&name!==thread.name){await api(`/api/threads/${thread.id}/rename`,{name});cache.summary(thread.id,{name});threads=cache.list();}}catch(e){error(e);}finally{input.remove();renderList();}};
 input.onkeydown=e=>{e.stopPropagation();if(e.key==='Enter'){e.preventDefault();void finish(true);}else if(e.key==='Escape'){e.preventDefault();void finish(false);}};input.onblur=()=>void finish(true);
}
function renderHeader(){
 if($('conversation-tools-toggle'))$('conversation-tools-toggle').hidden=scope==='coordinator';
 if(isRoom(scope)){if(rooms.entries.has(scope))renderRoomHeader();else{setLabel($('session-state'),'Original room access is unavailable. Saved draft retained.');$('send').disabled=true;$('mic').disabled=!voice.recording;$('room-controls').hidden=true;}return;}
 $('room-controls').hidden=true;delete $('main').dataset.roomId;setLabel($('draft-note'),'Enter to send · Shift+Enter for a new line');
 const t=scope==='coordinator'?null:current?.thread||threads.find(t=>t.id===scope),identity=sessionIdentity(t);setLabel($('title'),t?catalogTitle(t):'Coordinator');$('context-label').textContent=t?identity.isChild?'DELEGATED WORK':'DIRECT SESSION':'DIRECT YOUR COMPUTING';setLabel($('session-state'),t?`${sessionState(t).label} · ${t.cwd||'Work environment'}${identity.isChild&&!identity.canAcceptDirectInput?' · Guide through the parent task':''}`:status?.backend==='lax'?'Choose a Hollywood room. Stock coordinator is unavailable on this gateway.':'Ask here, or open work to guide it directly.');$('coordinator').classList.toggle('active',scope==='coordinator');$('session-menu').hidden=!t||(identity.isChild&&!identity.canAcceptDirectInput);$('parent').hidden=!identity.parentThreadId;$('parent').onclick=run(()=>open(identity.parentThreadId));const enabled=scope==='coordinator'?status?.backend!=='lax':!!current&&identity.canAcceptDirectInput;$('draft').disabled=!enabled;$('send').disabled=!enabled||!!deliveryFlight||!!pending;$('mic').disabled=!enabled||!voice.supported();$('draft').placeholder=!enabled?'Choose an available work conversation…':t?'Guide this session…':'Tell the coordinator what you need…';$('send-mode').hidden=!t;
}
function rowNode(row){let n;if(['turn','turnEnd'].includes(row.kind))return el('div','turn-label',row.kind==='turn'?`${row.status||'Turn'}${row.startedAt?' · '+new Date(row.startedAt*1000).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}):''}`:`${row.status||'Finished'}${row.text?' · '+row.text:''}`);
 if(row.kind==='request')return el('div','turn-label','Decision needed — open Needs you');
 if(row.kind==='attachments'){n=el('div','message');n.append(el('header','',row.title));for(const a of row.attachments||[]){if(!/^\/api\/files\/[a-zA-Z0-9_.-]+$/.test(a.url||''))continue;const url=clientURL(base,environmentPath(a.url,scope));if(/^image\//.test(a.mime||'')||/\.(png|jpe?g|webp|gif)$/i.test(a.name||a.url)){const image=el('img','attachment-image');image.src=url;image.alt=a.name||'Image attachment';image.loading='lazy';image.decoding='async';n.append(image);}const link=el('a','attachment-link',a.name||'Open attachment');link.href=url;link.target='_blank';link.rel='noopener noreferrer';n.append(link);}return n;}
 if(row.kind==='runs'){const details=el('details','activity run-block');const actions=row.rows.filter(r=>r.kind==='activity');details.append(el('summary','',`${actions.length||row.rows.length} operations · ${actions.at(-1)?.title||row.rows.at(-1)?.status||'Work activity'}`));for(const item of row.rows){const entry=el('div','run-entry');if(item.actionId)entry.dataset.roomActionId=item.actionId;entry.append(el('strong','',(item.title||item.type||'Operation')+' · '+(item.status||'')));if(item.text||item.detail)entry.append(el('pre','',item.detail||item.text));if(item.type==='commandExecution')entry.append(commandOutputButton({row:item,threadId:scope,api:executionApi(scope),isCurrent:executionCurrent(scope),create:el,button}));details.append(entry);}details.ontoggle=()=>{$('collapse-runs').hidden=!document.querySelector('.run-block[open]');};return details;}
 if(row.kind==='activity'){n=el('details','activity');n.append(el('summary','',`${row.title||row.type} · ${row.text||row.status||''}`));if(row.detail)n.append(el('pre','',row.detail));if(row.type==='commandExecution')n.append(commandOutputButton({row,threadId:scope,api:executionApi(scope),isCurrent:executionCurrent(scope),create:el,button}));return n;}
 n=el('article','message'+(row.kind==='user'?' user':''));const header=el('header');const icon=animatedSymbol(row.kind==='user'?'user':'codex',{size:28,label:row.roomAuthor||(row.kind==='user'?'You':'Codex')});header.append(icon);if(row.roomAuthor)header.append(el('span','',row.roomAuthor+(row.roomAudience==='direct'?' · Private':'')));const stamp=messageTime(row);if(stamp){const time=el('time','message-time',stamp.label);time.dateTime=stamp.iso;time.title=stamp.full;time.setAttribute('aria-label',stamp.full);header.append(time);}n.append(header);const body=el('div','body');body.dataset.immersionText=row.text||'';body.dataset.immersionId='web-message/'+row.id;const sourceScope=scope,route=isRoom(scope)?structuredClone(rooms.route(scope,{requireAvailable:false})):null;body._resolveUrl=url=>url.startsWith('/api/')?route?sourcePath(route.environment,url):environmentPath(url,sourceScope):url;body.append(richText(row.text,body._resolveUrl));n.append(body);if(row.kind!=='user'){const actions=el('div','message-actions');actions.append(button('Reply to this',()=>{if(scope!==sourceScope)throw Error('Open the original conversation first.');if(row.roomEventId)changeRoomTarget({...targetFor(scope),sourceEventId:row.roomEventId});$('draft').value=`About this update: “${String(row.text||'').slice(0,220)}”\n\n${$('draft').value}`;saveDraft(true);$('draft').focus();}),button('Speak',()=>voice.speak(row.text,{scope:sourceScope,environment:route?.environment})));n.append(actions);}return n;
}
function renderTranscript(){const data=cache.get(scope);if(!data)return;const list=$('transcript'),anchor=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top),anchorId=anchor?.dataset.id,anchorOffset=anchor?anchor.getBoundingClientRect().top-list.getBoundingClientRect().top:0,bottom=list.scrollHeight-list.scrollTop-list.clientHeight<90,previousHeight=list.scrollHeight,previousTop=list.scrollTop;
 const sourceRows=scope==='coordinator'?(data.turns||[]).flatMap(t=>[{id:t.id+'/user',kind:'user',title:'You',text:t.transcript||'',turnId:t.id,startedAtMs:t.created_at},{id:t.id+'/response',kind:'message',title:`Coordinator · ${t.state}`,text:t.response||t.error||'Working…',turnId:t.id,actions:t.actions,startedAtMs:t.timings?.first_response||t.timings?.first_final_response||t.timings?.response_completed}]):data.rows||[];
 const visibleRows=sourceRows;
 const rows=foldRuns(visibleRows);
 const old=new Map([...list.children].map(n=>[n.dataset.id,n]));let cursor=list.firstChild;for(const row of rows){let n=old.get(row.id);if(!samePresentation(n?._row,row)){const previous=n;n=rowNode(row);n.dataset.id=row.id;n._row=row;if(row.actions?.length){const actions=el('div','message-actions');for(const a of row.actions.filter(a=>a.threadId))actions.append(button(`Open ${a.name||'work'}`,()=>open(a.threadId)));n.append(actions);}if(previous){if(previous.open)n.open=true;previous.replaceWith(n);if(cursor===previous)cursor=n;}}old.delete(row.id);if(n!==cursor)list.insertBefore(n,cursor);cursor=n.nextSibling;}
 for(const n of old.values())n.remove();if(!rows.length){const empty=el('p','muted','No messages yet.');empty.dataset.id='empty';list.append(empty);}$('earlier').hidden=!data.hasEarlier;if(follow&&bottom)list.scrollTop=list.scrollHeight;else{const retained=anchorId&&[...list.children].find(n=>n.dataset.id===anchorId);if(retained)list.scrollTop+=retained.getBoundingClientRect().top-list.getBoundingClientRect().top-anchorOffset;else list.scrollTop=previousTop;}renderNotes();requestAnimationFrame(ackVisible);measureContentLayout();
}
function renderRequests(){if(isRoom(scope)){requestList.update(scope,[]);if(rooms.entries.has(scope))renderRoomAttention();return;}requestList.update(scope,scope==='coordinator'?[]:current?.pending||[]);}
function observeOutbox(thread,snapshot,paint=true){
 if(!cache.outbox(thread,snapshot))return;
 if(paint&&thread===scope){if(current)Object.assign(current,{outbox:cache.entity(thread).outbox,outgoing:cache.entity(thread).outbox.items});renderOutbox();}
}
function renderOutbox(){const holder=$('outbox');holder.replaceChildren();const outgoing=cache.entity(scope).outbox?.items??current?.outgoing??[];if(newPending){const row=el('div','outbox-row',`Task creation ${newPending.state||'pending'} · ${newPending.prompt.slice(0,80)}`);row.append(button('Check creation',reconcileStart));if(newPending.state==='unknown')row.append(button('Clear after reviewing work',()=>{if(confirm('Check recent work first. Clearing this receipt does not undo a created task; starting again could duplicate work. Clear it?')){newPending=null;write('newPending',null);renderOutbox();}}));holder.append(row);}if(pending&&!(pending.scope===scope&&outgoing.some(o=>o.id===pending.id))){const row=el('div','outbox-row',`${pending.scope===scope?'':'Another conversation · '}Saved send · ${pending.text.slice(0,90)} — checking delivery`);row.append(button('Check delivery',reconcilePending));holder.append(row);}for(const o of outgoing){
 if(['accepted','cancelled'].includes(o.state))continue;
 const origin={thread:scope,device:deviceId,navigation:queueNavigation,authority:queueAuthority};
 const sourceCurrent=()=>authenticated&&deviceId===origin.device&&queueAuthority===origin.authority;
 const uiCurrent=()=>sourceCurrent()&&scope===origin.thread&&queueNavigation===origin.navigation;
 const key=JSON.stringify([origin.device,origin.thread,o.id]),flight=queueActions.get(key),promoted=o.mode==='steer'&&['queued','held'].includes(o.state);
 const verified=queueVerifiedNavigation===queueNavigation&&!current?.detailError;
 const active=verified&&(current?.thread?.status?.type==='active'||current?.nativeExecutionTurn?.status==='inProgress');
 const idle=verified&&!active&&current?.thread?.status?.type==='idle';
 const label=active?'Steer now':idle?'Send now':'Checking status';
 const row=el('div','outbox-row',`${flight?.label||(promoted&&o.state==='queued'?'Sending to current work…':o.state)} · ${o.text?.slice(0,100)||'Message'}`);row.dataset.outgoingId=o.id;
 const act=async(action,extra={})=>{
  if(!uiCurrent()||queueActions.has(key))return;
  const label=action==='send'?(active?'Steering…':'Sending…'):'Updating message…';queueActions.set(key,{label});renderOutbox();
  try{
   const result=await api(`/api/threads/${origin.thread}/replies/${o.id}`,{action,...extra},{scope:origin.thread,acceptOutbox:data=>sourceCurrent()&&data.id===o.id&&typeof data.state==='string',paintOutbox:uiCurrent});
   if(result.id!==o.id||typeof result.state!=='string')throw Error('Message update was not acknowledged. Check its original delivery before trying again.');
   if(uiCurrent()&&!result.outbox)await refreshConversation();
  }catch(e){if(uiCurrent())error(e);}finally{queueActions.delete(key);if(uiCurrent())renderOutbox();}
 };
 const control=(text,fn,disabled=false)=>{const b=button(text,fn);b.disabled=!!flight||disabled;b.onpointerdown=e=>{if(document.activeElement===$('draft'))e.preventDefault();};row.append(b);};
 if(['failed','unknown','held','queued'].includes(o.state)){
  if(['queued','held'].includes(o.state))control(promoted?'Sending…':label,()=>act('send'),o.mode!=='queue'||!active&&!idle);
  if(['queued','held','failed'].includes(o.state))control('Edit',()=>{if(!uiCurrent()||queueActions.has(key))return;const text=prompt(o.state==='failed'?'Edit failed message':'Edit queued message',o.text);if(text&&uiCurrent())return act('edit',{text});},promoted);
  control('Remove',()=>act('remove'),promoted);
  if(['failed','unknown'].includes(o.state))control('Retry',()=>{if(!uiCurrent()||queueActions.has(key))return;if(o.state==='unknown'&&!confirm('Delivery is uncertain. Retrying can duplicate work. Check the conversation first. Retry anyway?'))return;return act('retry',{confirmUnknown:o.state==='unknown'});});
 }
 holder.append(row);
}}

async function ackVisible(){if(isRoom(scope)||document.visibilityState!=='visible'||!authenticated||document.querySelector('dialog[open]')||$('main').getBoundingClientRect().width===0)return;const data=cache.get(scope),root=$('transcript').getBoundingClientRect();if(!data)return;const rendered=[...$('transcript').children];const visible=n=>{const r=n.getBoundingClientRect();return r.top>=root.top&&r.bottom<=root.bottom;};if(scope==='coordinator'){for(const t of data.turns||[]){const n=rendered.find(n=>n.dataset.id===t.id+'/response'),key=deviceId+':'+t.id;if(t.state==='completed'&&n&&visible(n)&&!receipts.has(key)){receipts.add(key);api(`/api/voice/turns/${t.id}/presented`,{}).catch(()=>receipts.delete(key));}}}else if(data.catchup?.token&&rendered.length&&rendered.every(visible)){const key=data.catchup.token;if(!receipts.has(key)){receipts.add(key);api(`/api/threads/${scope}/catchup/read`,{token:key}).catch(()=>receipts.delete(key));}}}
function currentReadAnchor(id){if(id!==scope||follow)return null;const list=$('transcript'),first=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top);return first?._row?.rows?.[0]?.id||first?._row?.id||readerViews.get(id)?.anchor||null;}
function saveView(){if(!openedScope)return;const list=$('transcript'),first=[...list.children].find(n=>n.getBoundingClientRect().bottom>list.getBoundingClientRect().top);readerViews.set(readerKey(openedScope),{follow,anchor:first?.dataset.id,offset:first?first.getBoundingClientRect().top-list.getBoundingClientRect().top:0,top:list.scrollTop});}
function restoreView(view){if(!view||view.follow)return;const list=$('transcript'),anchor=[...list.children].find(n=>n.dataset.id===view.anchor);if(anchor)list.scrollTop+=anchor.getBoundingClientRect().top-list.getBoundingClientRect().top-view.offset;else list.scrollTop=view.top;}
async function open(id){
 queueNavigation++;queueVerifiedNavigation=-1;
 nativeSettingsPanel.closeForNavigation();
 ux.end(uxOpen,'superseded');ux.end(uxSend,'superseded');uxFresh=0;
 uxOpen=id!=='coordinator'&&!isRoom(id)?ux.begin('open'):0;
 historyReader.invalidate(id);
 if(isRoom(id))throw Error('Rooms are retired from NextComp. Original drafts and receipts are retained.');
 saveDraft();saveView();for(const panel of document.querySelectorAll(':popover-open'))panel.hidePopover();scope=id;if(isRoom(id)){const saved=read('roomView.'+deviceId+'.'+id,'all'),valid=roomLead(rooms.entry(id).room)?['all','conversation','activity','coordination','archive']:['all','conversation','activity'];roomView=valid.includes(saved)?saved:'all';}cache.protect(scope);readFeedback.select(scope);write('scope',scope);const view=readerViews.get(readerKey(id));follow=view?.follow??true;
 current=id==='coordinator'?null:cache.get(id)?.snapshot||null;openedDraftKey=draftKey();$('draft').value=read(openedDraftKey,'');openedScope=scope;$('requests').dataset.signature='';$('error').hidden=true;$('transcript').replaceChildren();document.body.classList.add('chat-open');renderHeader();renderList();renderTranscript();renderRequests();renderOutbox();renderNotes();restoreView(view);
 renderUsage();void refreshAccountUsage();await refreshConversation();if(scope===id){if(follow)$('transcript').scrollTop=$('transcript').scrollHeight;else restoreView(view);}
}
async function refreshConversation(before=null){
 const measurement=uxOpen,queueReadNavigation=queueNavigation,queueReadAuthority=queueAuthority;
 const selected=scope,selectedView=roomView,hasSnapshot=cache.has(selected);const request=lanes.run(readerKey(selected),before!==null,async()=>{
   if(isRoom(selected)){
    const view=selectedView,page=await rooms.read(selected,view,before);let detail=cache.get(selected)?.snapshot||null;
    if(scope===selected&&view===roomView){current=detail;cache.set(selected,{...page,snapshot:detail});}
    if(before===null)void lanes.run(selected+'/room-metadata',false,async()=>{
     const [state,attention]=await Promise.allSettled([rooms.detail(selected),rooms.attention(selected)]);
     const next={...(cache.get(selected)?.snapshot||detail),...(state.status==='fulfilled'?state.value:{detailError:state.reason.message})};
     if(state.status==='fulfilled')next.detailError=null;
     if(attention.status==='fulfilled'){next.roomAttention=attention.value;next.attentionError=null;}
     else next.attentionError=attention.reason.message;
     const retained=cache.get(selected),policyChanged=JSON.stringify(retained?.snapshot?.room?.leadPolicy)!==JSON.stringify(next.room?.leadPolicy);
     if(retained){retained.snapshot=next;if(policyChanged&&roomLead(rooms.entry(selected).room)){const h=rooms.history(selected,selectedView);retained.rows=h.rows();retained.hasEarlier=h.olderCursor!==null;retained.before=h.olderCursor;}}
     if(policyChanged&&scope===selected){renderTranscript();void refreshConversation();}
     if(scope===selected){current=next;renderHeader();renderRequests();renderNotes();}
    }).catch(e=>{if(scope===selected)error(e);});
   }else if(selected==='coordinator'){
    if(status?.backend==='lax'){cache.set(selected,{turns:[],hasEarlier:false});return;}
    const data=await api('/api/voice/history?conversationOnly=1'+(before?'&before='+encodeURIComponent(before):''));
    const old=cache.get(selected)||{},known=new Map((old.turns||[]).map(t=>[t.id,t]));for(const t of data.turns||[])known.set(t.id,t);
    const turns=[...known.values()].sort((a,b)=>(a.createdAt||a.created_at||0)-(b.createdAt||b.created_at||0));
    cache.set(selected,{...data,turns,...(!before&&old.hasEarlier?{hasEarlier:true,before:old.before}:{})});if(scope===selected)current=null;
   }else{
    const notesVersion=notesVersions.get(selected)||0,metadataTicket=cache.ticket();
    const data=await historyReader.read(selected,{before});
    const old=cache.get(selected)||{};
    if(!before&&notesVersion!==(notesVersions.get(selected)||0))data.notes=old.snapshot?.notes||[];
    const page=cache.head(selected,data,{older:!!before,ticket:metadataTicket,cursor:before,anchor:!follow?currentReadAnchor(selected):null});
    if(scope===selected){current=page.snapshot;if(!before&&queueNavigation===queueReadNavigation&&queueAuthority===queueReadAuthority)queueVerifiedNavigation=queueReadNavigation;if(!before&&uxOpen===measurement)uxFresh=measurement;}
   }
   if(scope===selected){renderHeader();renderTranscript();renderRequests();renderOutbox();renderNotes();}
 });
 try{return await (before?request:readFeedback.track(selected,request,{hasContent:hasSnapshot}));}
 catch(error){if(scope===selected)throw error;}
}
const historyDemand=new Map(),historyConsumed=new Map(),earlierReads=new Set();let edgeTimer,lastWheel=0;
function demandOlder(){historyDemand.set(scope,(historyDemand.get(scope)||0)+1);follow=false;scheduleEdge();}
function scheduleEdge(){clearTimeout(edgeTimer);edgeTimer=setTimeout(()=>{if($('transcript').scrollTop<100)void run(loadEarlier)();},150);}
async function loadEarlier(){
 const selected=scope,data=cache.get(selected),demand=historyDemand.get(selected)||0;
 if(!data?.hasEarlier||earlierReads.has(selected)||demand<=(historyConsumed.get(selected)||0))return;
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
   const data=await historyReader.read(id,{background:true});
   if(notesVersion!==(notesVersions.get(id)||0))data.notes=cache.get(id)?.snapshot?.notes||[];
   const page=cache.head(id,data,{ticket,anchor:currentReadAnchor(id)});
   if(scope===id){current=page.snapshot;renderHeader();renderTranscript();renderRequests();renderOutbox();renderNotes();}
  }).catch(()=>{}).finally(()=>{backgroundFlights.delete(id);if(backgroundHeads.size&&!backgroundTimer)backgroundTimer=setTimeout(pumpBackgroundHeads,500);});
 }
 if(backgroundHeads.size&&!backgroundTimer)backgroundTimer=setTimeout(pumpBackgroundHeads,1000);
}
async function refreshList({invalidate=false}={}){
 const filter=$('filter').value,profile=deviceId,query=$('search').value.trim();
 return lanes.run('catalog/'+profile+'/'+filter+'/'+query,false,async()=>{
  const metadataTicket=cache.ticket();let data;catalogFailure=false;
  const suffix='?'+new URLSearchParams({... (filter==='archived'?{archived:'true'}:{}),...(query?{search:query}:{})});
  try{data=await api('/api/threads'+suffix);}catch(error){catalogFailure=true;data={threads};}
  if(deviceId!==profile||$('filter').value!==filter||$('search').value.trim()!==query)return;
  catalogSettled=true;catalogChecksIncomplete=catalogFailure||data.environments?.some(e=>!e.available)===true||data.catalogPartial===true;threads=cache.catalog((data.threads||[]).map(t=>({...t,archived:filter==='archived'})),metadataTicket);$('catalog-note').textContent=(data.environments?.some(e=>!e.available)?'An environment is unavailable; its last known work remains visible.':data.catalogPartial?'Showing a bounded inventory. Search or open known work.':'');renderList();setSymbolMotionReady(true);
  for(const id of cache.catalogChanges){historyReader.invalidate(id);if(id===scope)scheduleRefresh({head:true});else queueBackgroundHead(id);}
 },{invalidate});
}
async function send(){const text=$('draft').value.trim();if(!text)return;if(deliveryFlight)await deliveryFlight;if(pending)throw Error('Check the saved message delivery before sending another.');if($('send').disabled)throw Error('This conversation is currently unavailable for input. Your draft remains saved.');saveDraft();const message={...pendingFor(scope,text,$('send-mode').value),deviceId,draftKey:draftKey(),draftRevision:read('revision.'+draftKey(),0),state:'prepared'};
 if(isRoom(scope)){if(new TextEncoder().encode(text).length>6000)throw Error('Room messages are limited to 6000 UTF-8 bytes. Draft retained.');const entry=rooms.entry(scope),route=structuredClone(rooms.route(scope)),target={...targetFor(scope)};assertLeadTarget(entry.room,target);if(roomLead(entry.room)&&['coordination','archive'].includes(roomView))throw Error('Open the lead conversation to send new input. Your draft is retained.');message.room=entry.roomId;message.route=route;message.body={operationId:message.id,kind:'request',body:text,audience:target.audience,...(target.recipientId?{recipientId:target.recipientId}:{}),...(target.sourceEventId?{sourceEventId:target.sourceEventId}:{})};}
 else if(scope==='coordinator'&&status?.backend==='lax')throw Error('Choose an exact Hollywood room on this gateway.');
 ux.end(uxSend,'superseded');uxSend=!isRoom(scope)&&scope!=='coordinator'?ux.begin('send'):0;
 const measurement=uxSend;
 try{persistPending(message);}catch(error){ux.end(measurement,'failed');throw error;}
 ux.mark(measurement,'send_feedback_committed');try{await deliverPending();}catch(error){ux.end(measurement,'unknown');throw error;}}
function clearDelivered(message,receipt){
 if(pending?.id!==message.id||!deliveryAccepted(message,receipt))throw Error('Original delivery is unconfirmed. Draft and receipt retained.');
 write('delivered.'+deviceId+'.'+message.id,{intent:message,receipt});
 const key=message.draftKey||(!isRoom(message.scope)?draftKey(message.scope):null);
 // Legacy journals have no generation: keep their text conservatively.
 if(key&&message.draftRevision!==undefined&&read('revision.'+key,0)===message.draftRevision&&read(key,'').trim()===message.text){write(key,'');if(message.scope===scope&&key===draftKey()&&$('draft').value.trim()===message.text)$('draft').value='';}
 persistPending(null);
}
async function deliverPending(){if(deliveryFlight)return deliveryFlight;deliveryFlight=performPending();try{return await deliveryFlight;}finally{deliveryFlight=null;renderHeader();}}
async function readOriginal(p){return p.room?rooms.call(p.route,'commands/'+encodeURIComponent(p.id)):api(p.scope==='coordinator'?`/api/voice/turns/${p.id}`:`/api/replies/${p.id}`,undefined,{scope:p.scope});}
async function performPending(){const p=pending;if(!p||p.deviceId!==deviceId)throw Error('Saved delivery belongs to another paired device.');if(p.state!=='prepared')return checkOriginalPending(p);$('send').disabled=true;
 persistPending({...p,state:'dispatched'});
 try{
  if(p.room)await rooms.call(p.route,'rooms/'+p.room+'/messages',p.body);
  else if(p.scope==='coordinator'){await api('/api/voice/start',{threadId:null});await api('/api/voice/text',{turnId:p.id,text:p.text});}
  else await api(`/api/threads/${p.scope}/reply`,{id:p.id,text:p.text,mode:p.mode});
  // Every path reads its original durable receipt, even after a successful POST.
  await checkOriginalPending(p);
 }catch(e){if(pending?.id===p.id)persistPending({...pending,state:'unknown',error:e.message});throw e;}
 finally{renderHeader();}
}
async function checkOriginalPending(p){
 const receipt=await readOriginal(p);
 if(!deliveryAccepted(p,receipt))throw Error('Original delivery is unconfirmed. Draft and receipt retained.');
 if(pending?.id===p.id&&p.scope===scope)ux.mark(uxSend,'send_admitted_callback');
 write('delivered.'+deviceId+'.'+p.id,{intent:p,receipt});
 // Keep the original local state through a failed refresh, even after a valid
 // acknowledgement. Never turn refresh failure into another submission.
 if(p.room){const page=await rooms.call(p.route,'rooms/'+p.room+'/history?view=conversation&limit=100&before='+Number.MAX_SAFE_INTEGER);new RoomHistory({authorityId:p.route.binding.authorityId,principalId:p.route.binding.principalId,roomId:p.room},{view:'conversation'}).apply(page);}
 else if(p.scope==='coordinator')await api('/api/voice/history?conversationOnly=1');
 else if(scope===p.scope)await refreshConversation();
 else await historyReader.read(p.scope);
 clearDelivered(p,receipt);
 if(p.room||p.scope==='coordinator')await refreshConversation();
 await refreshList({invalidate:true});
}
async function reconcilePending(){if(deliveryFlight)return deliveryFlight;if(!pending)return;const p=pending;if(p.deviceId!==deviceId)throw Error('This saved send belongs to another browser pairing.');
 const inspect=async()=>{try{await checkOriginalPending(p);}catch(e){if(pending?.id===p.id)persistPending({...pending,state:'unknown',error:e.status===404?'Original receipt missing; delivery remains uncertain. No resubmission.':e.message});throw e;}};
 deliveryFlight=inspect();try{return await deliveryFlight;}finally{deliveryFlight=null;renderHeader();}
}
let runtimeConnected=null;const unavailableEnvironments=new Set();
function handleEvent(m){
 if(m.type==='nativeAuthBoundary'){catalogChecksIncomplete=true;renderList();scheduleRefresh({head:false,catalog:true,attention:true});}
 if(m.type==='nativeAuthBoundary'||m.type==='status'&&m.connected===false||m.type==='environmentStatus'&&(m.available??m.connected)===false){queueAuthority++;queueVerifiedNavigation=-1;renderOutbox();}
 nativeSettingsPanel.event(m);
 conversationToolsPane.event(m);
 if(m.type==='hollywoodInbox'||m.type==='roomEvent'||m.type==='roomNativeState'){scheduleRefresh({head:isRoom(scope),catalog:true});return;}
 if(m.type==='environmentStatus'){
  const available=m.available??m.connected;
  const recovered=available===true&&unavailableEnvironments.delete(m.environmentId);
  if(available===false){unavailableEnvironments.add(m.environmentId);accountUsage.unavailable(m.environmentId);}
  if(available===true&&accountSource().environment===m.environmentId)void refreshAccountUsage();
  renderUsage();scheduleRefresh({head:recovered&&environmentOf(scope)===m.environmentId,catalog:true,attention:recovered});return;
 }
 if(immersion.handle(m))return;
 if(m.type==='rateLimits'){const environment=m.environmentId||'current';if(environment==='current')status={...status,usage:m.usage};accountUsage.limits(environment,m.usage);renderUsage();return;}
 if(m.type==='status'){
  const recovered=runtimeConnected===false&&m.connected===true;runtimeConnected=m.connected;
  setLabel($('connection'),m.connected===false?'Codex reconnecting…':'Connected');
  if(m.connected===false){catalogChecksIncomplete=true;renderList();scheduleRefresh({head:false,catalog:true});}else if(recovered)scheduleRefresh({head:true,catalog:true,attention:true});return;
 }
 const threadId=m.threadId||m.notification?.thread_id||m.event?.params?.threadId;
 if(threadId&&['timeline','sessionInteraction','sessionStarted','conversationChanged','notification','reply'].includes(m.type))historyReader.invalidate(threadId);
 if(threadId&&m.type==='codex'&&['turn/started','item/started','item/completed'].includes(m.event?.method))historyReader.invalidate(threadId);
 if(m.type==='conversationChanged'){if(m.kind==='revert'){cache.replaceHistory(threadId,m.historyHead);loadSerial++;}scheduleRefresh({head:threadId===scope,catalog:true});return;}
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
  if(!cache.inventory.includes(threadId)&&!m.thread){scheduleRefresh({catalog:true});return;}
  const changed=cache.summary(threadId,patch,{discover:!!m.thread});threads=cache.list();renderList();
  if(threadId===scope){if(changed&&(['assistant','codex','user'].includes(patch.previewRole)||m.type==='sessionInteraction'))scheduleRefresh({head:true});}
  else if(changed&&(['assistant','codex','user'].includes(patch.previewRole)||m.type==='sessionInteraction'))queueBackgroundHead(threadId);
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
 if(['attention','attentionResolved','request'].includes(m.type)){scheduleRefresh({head:threadId===scope,attention:true,catalog:true});return;}
 if(m.type==='reply'&&m.outbox){observeOutbox(threadId,m.outbox);scheduleRefresh({head:m.state==='accepted'&&threadId===scope,catalog:true});return;}
 if(['reply','turnRecovery','message'].includes(m.type)){scheduleRefresh({head:threadId===scope,catalog:true});return;}
 if(m.type==='codex'){
  if(['turn/started','turn/completed','thread/status/changed','serverRequest/resolved'].includes(m.event?.method))scheduleRefresh({head:threadId===scope,catalog:true});
  else if(threadId===scope&&['item/started','item/completed'].includes(m.event?.method))scheduleRefresh({head:true});
  return;
 }
 // Unknown and aggregate activity events do not invalidate a conversation.
 // The independent periodic reconciliation remains the compatibility fallback.
}
function connectEvents(){
 clearTimeout(reconnectTimer);if(!authenticated)return;ws?.close();unavailableEnvironments.clear();
 ws=new WebSocket(clientURL(base,'events').replace(/^http/,'ws'));const own=ws;
 ws.onopen=()=>{$('connection').textContent='Connected';void run(async()=>{await Promise.all([refreshList(),refreshConversation(),refreshAttention()]);await reconcilePending();})()};
 ws.onmessage=e=>{if(ws!==own||!authenticated)return;let m;try{m=JSON.parse(e.data);}catch{return;}handleEvent(m);};
 ws.onclose=()=>{if(ws!==own||!authenticated)return;nativeSettingsPanel.closeForNavigation();$('connection').textContent='Reconnecting…';reconnectTimer=setTimeout(connectEvents,3000);};ws.onerror=()=>own.close();
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
   void run(()=>Promise.all([...(refreshHead?[refreshConversation()]:[]),...(catalog?[refreshList({invalidate:true})]:[]),...(attention?[refreshAttention()]:[])]))();
  }
 },Math.max(120,head?(scope==='coordinator'?1000:500)-(performance.now()-lastHeadRefresh):120));
}
async function refreshAttention(){const data=status?.backend==='lax'?{notifications:[]}:await api('/api/attention');attentionItems=data.notifications;renderNotes();renderList();}

function readThemePreference(){try{return JSON.parse(localStorage.getItem('nextcomp.web.theme'));}catch{return null;}}
async function boot(){try{try{status=await api('/api/status');}catch(e){if(e.status!==401)throw e;try{await api('/web/tailnet-login',{});}catch(loginError){if([403,404].includes(loginError.status))throw e;throw loginError;}status=await api('/api/status');}deviceId=status.deviceId;voice.profile=deviceId;voice.legacyProfile=read('deviceId')||deviceId;if(await importPreviousProfile(base,deviceId)){scope=read('scope','coordinator');pending=read('pending',null);newPending=read('newPending',null);if(isRoom(scope)){write('retiredRoomScope',scope);scope='coordinator';write('scope',scope);}if(pending?.room){write('retiredRoomPending.'+pending.deviceId+'.'+pending.id,pending);pending=null;write('pending',null);}const preference=readThemePreference();if(preference)window.NextCompTheme?.select(preference);}const retirement=await api('/web/environments').catch(()=>null);if(retirement?.retiredEnvironmentIds?.length){const ids=new Set(retirement.retiredEnvironmentIds);purgeRetiredStorage(localStorage,retirement.retiredEnvironmentIds);for(const record of await voiceJournal('list'))if(retiredValue(record,ids))await voiceJournal('delete',record.id);scope=read('scope','coordinator');pending=read('pending',null);newPending=read('newPending',null);}authenticated=true;ux.foreground(document.visibilityState==='visible');$('startup').hidden=true;$('pairing').hidden=true;$('app').hidden=false;$('host-info').textContent=`${status.host} · ${status.version} · ${status.device}`;if(read('deviceId')&&read('deviceId')!==deviceId){const original=read('deviceId');if(pending)write('detachedPending.'+original,pending);if(newPending)write('detachedStart.'+original,newPending);pending=null;newPending=null;write('pending',null);write('newPending',null);scope='coordinator';}write('deviceId',deviceId);$('cwd').value=status.defaultCwd||'';renderUsage();connectEvents();const initial=await Promise.allSettled([refreshList(),open(scope),refreshAttention()]);if(initial[0].status==='rejected')error(initial[0].reason);else if(initial[1].status==='rejected')error(initial[1].reason);await voice.restore();void voice.loadCapabilities();if(pending)await reconcilePending();if(newPending)await reconcileStart();}catch(e){if(e.status===401){$('startup').hidden=true;$('pairing').hidden=false;$('app').hidden=true;}else error(e);}}
$('pair-form').onsubmit=async e=>{e.preventDefault();const b=$('pair-form').querySelector('button');b.disabled=true;try{await api('/web/pair',Object.fromEntries(new FormData(e.target)));await boot();}catch(err){$('pair-error').textContent=err.message;}finally{b.disabled=false;}};
$('composer').onsubmit=run(async e=>{e.preventDefault();await send();});$('draft').oninput=()=>saveDraft(true);$('draft').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();void run(send)();}};
$('send').onpointerdown=e=>{if(document.activeElement===$('draft'))e.preventDefault();};
$('coordinator').onclick=run(()=>open('coordinator'));$('refresh').onclick=run(async()=>{seatOrder=[];await refreshList({invalidate:true});});let catalogSearchTimer;$('search').oninput=()=>{renderList();clearTimeout(catalogSearchTimer);catalogSearchTimer=setTimeout(()=>void run(()=>refreshList({invalidate:true}))(),250);};$('filter').onchange=run(async()=>{needsPreviousView=null;seatOrder=[];await refreshList({invalidate:true});});$('back').onclick=()=>{saveDraft();saveView();document.body.classList.remove('chat-open');seatOrder=[];renderList();};$('earlier').onclick=run(async()=>{demandOlder();await loadEarlier();});$('latest').onclick=()=>{follow=true;cache.followLatest(scope);renderTranscript();$('transcript').scrollTop=$('transcript').scrollHeight;};$('transcript').onscroll=()=>{follow=$('transcript').scrollHeight-$('transcript').scrollTop-$('transcript').clientHeight<90;void ackVisible();scheduleEdge();};
$('settings').onclick=run(async()=>{$('settings-dialog').showModal();renderUsage();const prefs=await api('/api/coordinator/reporting');$('report-enabled').checked=prefs.enabled;$('report-interval').value=prefs.intervalMinutes;$('report-stale').value=prefs.staleAfterMinutes;});$('save-reporting').onclick=run(async()=>{await api('/api/coordinator/reporting',{enabled:$('report-enabled').checked,intervalMinutes:Number($('report-interval').value),staleAfterMinutes:Number($('report-stale').value)});$('settings-dialog').hidePopover?.();$('settings-dialog').close();});$('notifications').onclick=run(async()=>{if(!('Notification' in window))throw Error('Browser notifications are unavailable.');const permission=await Notification.requestPermission();$('notifications').textContent=permission==='granted'?'Notifications enabled while connected':`Notifications ${permission}`;});
$('logout').onclick=run(async()=>{saveDraft();await voice.close();await api('/web/logout',{});authenticated=false;queueAuthority++;queueVerifiedNavigation=-1;ux.foreground(false);ws?.close();clear();location.reload();});$('mic').onclick=run(()=>voice.capture(scope,voice.recording?{}:roomVoiceOptions()));$('voice-send').onclick=run(async()=>{await voice.send(scope);await refreshConversation();});$('voice-clear').onclick=run(()=>voice.discard());
$('new-task').onclick=run(async()=>{if(newPending){await reconcileStart();return;}$('new-prompt').value=read('newDraft','');$('new-dialog').showModal();const data=await api('/api/projects');$('projects').replaceChildren(...data.projects.map(p=>{const n=el('option');n.value=p.cwd;return n;}));});$('new-prompt').oninput=()=>write('newDraft',$('new-prompt').value);$('cancel-new').onclick=()=>$('new-dialog').close();
async function reconcileStart(){if(startFlight)return startFlight;startFlight=performStart();try{return await startFlight;}finally{startFlight=null;}}
async function performStart(){if(!newPending)return;const p=newPending;let result;try{result=await api(`/api/session-starts/${p.id}`);}catch(e){if(e.status!==404)throw e;try{result=await api('/api/threads',p);}catch(e){if(e.status&&e.status<500){newPending=null;write('newPending',null);}throw e;}}newPending={...p,state:result.state};write('newPending',newPending);renderOutbox();if(result.state==='started'){newPending=null;write('newPending',null);write('newDraft','');$('new-dialog').close();await refreshList({invalidate:true});await open(result.thread_id);}else if(['failed','unknown'].includes(result.state)){if(result.state==='failed'){newPending=null;write('newPending',null);}throw Error(result.error||'Task creation uncertain. Check recent work before starting again.');}else $('new-error').textContent='Task accepted. Waiting for the backend to create it…';}
$('new-form').onsubmit=async e=>{e.preventDefault();try{if(!newPending){newPending={id:crypto.randomUUID(),cwd:$('cwd').value,prompt:$('new-prompt').value,permissions:$('permissions').value};write('newPending',newPending);}await reconcileStart();}catch(e){$('new-error').textContent=e.message;}};
function setEfforts(){const model=models.find(m=>m.model===$('model').value);$('effort').replaceChildren(...(model?.efforts||[]).map(e=>{const o=el('option','',e.reasoningEffort);o.value=e.reasoningEffort;return o;}));$('effort').value=current?.turnSettings?.effort||model?.defaultEffort;serviceTierControl.refresh();$('turn-mode').value=current?.turnSettings?.mode==='plan'?'plan':'build';}$('model').onchange=setEfforts;
for(const [id,action,body] of [['watch','watch',()=>({enabled:!current?.watched})],['stop','interrupt',()=>({})],['resume','queue/resume',()=>({})]])$(id).onclick=run(async()=>{await api(`/api/threads/${scope}/${id==='archive'&&$('filter').value==='archived'?'unarchive':action}`,body());$('controls-dialog').hidePopover?.();$('controls-dialog').close();await refreshConversation();await refreshList({invalidate:true});});
$('archive').onclick=run(async()=>{const thread=threads.find(t=>t.id===scope)||{...current?.thread,id:scope};closeControls();await setSessionArchived(thread,thread.archived!==true);});
$('rename').onclick=run(async()=>{const name=prompt('Session name',current?.thread?.name||'');if(name){await api(`/api/threads/${scope}/rename`,{name});await refreshConversation();await refreshList({invalidate:true});}});$('save-turn').onclick=run(async()=>{await api(`/api/threads/${scope}/settings`,{model:$('model').value,effort:$('effort').value,mode:$('turn-mode').value==='plan'?'plan':'default',serviceTier:serviceTierControl.value()});$('controls-dialog').hidePopover?.();$('controls-dialog').close();await refreshConversation();});
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installEvent=e;$('install').hidden=false;});$('install').onclick=run(async()=>{await installEvent?.prompt();installEvent=null;$('install').hidden=true;});
window.addEventListener('online',()=>{if(authenticated){connectEvents();scheduleRefresh();}});window.addEventListener('offline',()=>{$('connection').textContent='Offline · drafts saved';});document.addEventListener('visibilitychange',()=>{ux.foreground(document.visibilityState==='visible'&&authenticated);if(document.visibilityState==='visible'&&authenticated){scheduleRefresh();if(ws?.readyState!==WebSocket.OPEN)connectEvents();}});window.addEventListener('pagehide',()=>{ux.foreground(false);saveDraft();void voice.close();});window.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='k'){e.preventDefault();$('search-panel').hidden=false;$('search').focus();}if(e.key==='Escape'&&!document.querySelector('dialog[open],:popover-open')&&innerWidth<=760)document.body.classList.remove('chat-open');});
setInterval(()=>{if(!authenticated||polling||document.visibilityState!=='visible')return;renderUsage();polling=true;void run(async()=>{await Promise.all([refreshConversation(),refreshAttention()]);if(newPending)await reconcileStart();})().finally(()=>polling=false);void run(()=>refreshList())();},10000);
if('serviceWorker' in navigator)navigator.serviceWorker.register(clientURL(base,'sw.js'),{scope:base.pathname}).catch(()=>{});
const immersion=new ImmersionLayer(api,base,text=>{$('immersion-state').textContent=text;$('immersion-enabled').checked=immersion.enabled;});
$('immersion-enabled').onchange=run(()=>immersion.setEnabled($('immersion-enabled').checked));
void boot().then(()=>{if(authenticated)void immersion.load();});

function accountSource(){
 if(isRoom(scope)){try{const route=rooms.route(scope,{requireAvailable:false});return {environment:route.environment,name:route.name};}catch{return {environment:null,name:'Original room source unavailable'};}}
 const environment=environmentOf(scope)||'current',name=threads.find(t=>t.id===scope)?.environmentName||(environment==='current'?status?.host:rooms.sources.find(s=>s.id===environment)?.name)||environment;
 return {environment,name};
}
async function refreshAccountUsage(){
 const source=accountSource(),owner=deviceId;if(!source.environment||!owner||!accountUsage.needsStatus(source.environment))return;
 const key=owner+'/'+source.environment;if(accountFlights.has(key))return accountFlights.get(key);
 const work=(async()=>{try{const value=await api(sourcePath(source.environment,'/api/status'),undefined,{timeout:4000});if(deviceId===owner)accountUsage.status(source.environment,value,source.name);}catch{if(deviceId===owner)accountUsage.unavailable(source.environment);}finally{accountFlights.delete(key);if(deviceId===owner)renderUsage();}})();accountFlights.set(key,work);return work;
}
function renderUsage(){
 accountUsage.profile(deviceId);if(status?.deviceId===deviceId&&!accountUsage.observations.has('current'))accountUsage.status('current',status,status.host);
 const source=accountSource(),usage=accountUsage.view(source.environment,source.name);
 setLabel($('usage'),usage.label);$('usage').title=usage.provenance+' · '+usage.reset+' · '+usage.freshness;
 const toolbarUsage=$('conversation-usage');if(toolbarUsage){setLabel(toolbarUsage.querySelector('.account-allowance'),usage.label);setLabel(toolbarUsage.querySelector('.account-source'),' · '+usage.source);toolbarUsage.dataset.usageState=usage.state;toolbarUsage.dataset.usageEnvironment=usage.environment||'';toolbarUsage.title=usage.reset+' · '+usage.freshness;toolbarUsage.setAttribute('aria-label',usage.provenance+'; '+usage.label+'; '+usage.reset+'; '+usage.freshness);}
 const box=$('account-usage');box.replaceChildren(el('strong','',usage.label),el('p','',usage.provenance),el('p','',usage.reset),el('p','',usage.credits),el('p','muted',usage.creditStale?'Last known or unavailable credit observation':'Current credit observation'),el('p','muted',usage.continuation),el('p','muted',usage.freshness));
}
$('usage').onclick=()=>{$('settings-dialog').showModal();renderUsage();};

function renderNotes(){if(review)review.update({scope,pending:isRoom(scope)?(current?.roomAttention||[]).filter(v=>v.attention?.state==='pending').map(v=>({id:v.attention.id})):current?.pending||[],notes:current?.notes||[],rows:cache.get(scope)?.rows||[],notifications:attentionItems});}

const removingNotes=new Set(),notesVersions=new Map();
$('collapse-runs').onclick=()=>{for(const run of document.querySelectorAll('.run-block[open]'))run.open=false;$('collapse-runs').hidden=true;};
function page(direction){follow=false;const list=$('transcript');list.scrollBy({top:direction*list.clientHeight,behavior:'auto'});if(direction<0){demandOlder();if(list.scrollTop<100)void run(loadEarlier)();}}
$('page-up').onclick=()=>page(-1);$('page-down').onclick=()=>page(1);
$('toggle-keyboard').onclick=()=>{$('composer').classList.toggle('keyboard-open');if($('composer').classList.contains('keyboard-open'))$('draft').focus();};
let playbackVolume=read('playbackVolume',1);function volume(){voice.volume=playbackVolume;if(voice.audio)voice.audio.volume=playbackVolume;$('playback-volume').textContent=playbackVolume===0?'Muted':`${playbackVolume*100}%`;}$('playback-volume').onclick=()=>{playbackVolume=playbackVolume===0?.5:playbackVolume===.5?1:0;write('playbackVolume',playbackVolume);volume();};volume();
// One bottom control surface; reading space is not consumed by a second header.
const toolbar=document.querySelector('.transcript-toolbar'),header=document.querySelector('.conversation-header');
const conversationToolsToggle=button('Context & files',()=>{closeControls();return conversationToolsPane.show();});conversationToolsToggle.id='conversation-tools-toggle';conversationToolsToggle.hidden=scope==='coordinator';$('controls-dialog').append(conversationToolsToggle);
const conversationUsage=button('',()=>{renderUsage();anchorPanel($('settings-dialog'),conversationUsage);});conversationUsage.id='conversation-usage';conversationUsage.className='conversation-usage';conversationUsage.dataset.accountUsage='';conversationUsage.append(el('span','account-allowance','Usage unavailable'),el('span','account-source'));toolbar.append(conversationUsage);
toolbar.prepend($('back'));toolbar.append($('parent'),$('session-menu'));for(const control of toolbar.querySelectorAll('button'))control.type='button';$('composer').append(toolbar);header.classList.add('footer-identity');$('composer').prepend(header);
$('draft').addEventListener('focus',()=>{$('composer').classList.add('keyboard-open');});

$('work-list').addEventListener('scroll',()=>{listReadingUntil=Date.now()+800;},{passive:true});

window.addEventListener('keydown',e=>{if(e.code==='F8'&&!e.repeat&&!document.querySelector('dialog[open],:popover-open')){e.preventDefault();void run(()=>voice.capture(scope,voice.recording?{}:roomVoiceOptions()))();}});

const inputRow=el('div','input-row');inputRow.append($('toggle-keyboard'),$('mic'),$('draft'),$('send'));const composer=$('composer');composer.insertBefore(inputRow,document.querySelector('.composer-actions'));
const deliveryLabel=el('label','','Next message');deliveryLabel.append($('send-mode'));$('controls-dialog').append(deliveryLabel);
function anchoredControls(){anchorPanel($('controls-dialog'),$('session-menu'));}
let controlsGeneration=0;
const modelsStatus=el('p','muted');modelsStatus.id='models-status';modelsStatus.setAttribute('role','status');$('model').parentElement.before(modelsStatus);
function closeControls(){++controlsGeneration;$('controls-dialog').hidePopover?.();$('controls-dialog').close();}
$('controls-dialog').addEventListener('beforetoggle',event=>{if(event.newState==='closed')++controlsGeneration;});
$('session-menu').onclick=run(async()=>{
 const original=scope,generation=++controlsGeneration,panel=$('controls-dialog');
 const valid=()=>generation===controlsGeneration&&scope===original&&panel.matches(':popover-open');
 $('watch').textContent=current?.watched?'Unfollow updates':'Follow updates';
 $('archive').textContent=current?.thread?.archived===true||$('filter').value==='archived'?'Restore':'Archive';
 models=[];$('model').replaceChildren();$('effort').replaceChildren();
 for(const id of ['model','effort','turn-mode','service-tier','save-turn'])$(id).disabled=true;
 modelsStatus.textContent='Loading model settings…';anchoredControls();
 try{
  const data=await api('/api/models',undefined,{scope:original});if(!valid())return;
  models=data.models||[];
  if(!models.length)throw Error('No models were returned.');
  $('model').replaceChildren(...models.map(m=>{const option=el('option','',m.name||m.model);option.value=m.model;return option;}));
  $('model').value=current?.turnSettings?.model||models[0]?.model||'';setEfforts();
  for(const id of ['model','effort','turn-mode','save-turn'])$(id).disabled=false;
  modelsStatus.textContent='';
 }catch(error){if(valid())modelsStatus.textContent='Model settings unavailable. '+error.message;}
});
document.querySelector('#controls-dialog .close').onclick=e=>{e.preventDefault();closeControls();};

$('transcript').addEventListener('wheel',event=>{if(event.deltaY<0){const now=performance.now();if(now-lastWheel>220)demandOlder();lastWheel=now;scheduleEdge();}},{passive:true});
let touchY=null,touchDemanded=false;$('transcript').addEventListener('touchstart',event=>{touchY=event.touches[0]?.clientY;touchDemanded=false;},{passive:true});
$('transcript').addEventListener('touchmove',event=>{if(!touchDemanded&&touchY!=null&&(event.touches[0]?.clientY||0)-touchY>3){touchDemanded=true;demandOlder();}scheduleEdge();},{passive:true});
$('transcript').addEventListener('keydown',event=>{if(['PageUp','ArrowUp','Home'].includes(event.key))demandOlder();});
function samePresentation(a,b){
 if(a===b)return true;if(!a||a.kind!==b.kind)return false;
 if(a.kind==='runs')return a.rows.length===b.rows.length&&a.rows.every((r,i)=>samePresentation(r,b.rows[i]));
 if(!['title','text','detail','status','phase','type','roomAuthor','roomAudience','roomRecipient','roomEventId'].every(key=>a[key]===b[key]))return false;
 const equalList=(left=[],right=[],keys)=>left.length===right.length&&left.every((item,i)=>keys.every(key=>item[key]===right[i][key]));
 return equalList(a.attachments,b.attachments,['name','url','mime'])&&equalList(a.actions,b.actions,['threadId','name','type']);
}

// Catalog actions share the same bottom reach zone as the Android catalog.
const sidebar=$('sidebar'),catalogDock=el('div','catalog-dock');catalogDock.id='catalog-dock';
const find=button('Find',()=>{const panel=$('search-panel');panel.hidden=!panel.hidden;if(!panel.hidden)$('search').focus();});find.id='find-work';
const searchPanel=el('div','search-panel');searchPanel.id='search-panel';searchPanel.hidden=true;searchPanel.append(document.querySelector('.search'),document.querySelector('.list-tools'));
const usageNode=$('usage'),usageValue=el('span','usage-value',usageNode.textContent);usageValue.id='usage';usageNode.replaceWith(usageValue);$('settings').append(usageValue);usageValue.onclick=null;
function dockIcon(control,name,label){control.type='button';control.setAttribute('aria-label',label);control.title=label;const preserved=control.id==='settings'?usageValue:null;control.replaceChildren();control.append(animatedSymbol(name,{size:32}));if(preserved)control.append(preserved);}
dockIcon($('coordinator'),'nextcomp','Coordinator');dockIcon($('new-task'),'editnote','New task');dockIcon(find,'search','Find work');dockIcon($('needs-you-toggle'),'inbox','Needs you');dockIcon($('settings'),'computer','Settings and account usage');
$('needs-you-toggle').onclick=run(toggleNeedsYou);
catalogDock.append($('coordinator'),$('new-task'),find,$('needs-you-toggle'),$('settings'));
const oldFooter=sidebar.querySelector('footer');oldFooter.replaceChildren(catalogDock,$('connection'));sidebar.append(searchPanel,oldFooter);document.querySelector('.brand').hidden=true;document.querySelector('.work-heading').hidden=true;document.querySelector('.nav-row').hidden=true;

function panelViewport(){const v=window.visualViewport;return {left:v?.offsetLeft||0,top:v?.offsetTop||0,width:v?.width||innerWidth,height:v?.height||innerHeight};}
function positionPanel(panel,control){const viewport=panelViewport(),frame=panelFrame(viewport);Object.assign(panel.style,{position:'fixed',margin:'0',bottom:'auto',left:frame.left+'px',top:frame.top+'px',width:frame.width+'px',maxWidth:frame.width+'px',maxHeight:frame.height+'px'});const at=anchoredPanelPlacement(viewport,control.getBoundingClientRect(),panel.getBoundingClientRect());panel.style.left=at.left+'px';panel.style.top=at.top+'px';}
function anchorPanel(panel,control){panel.popover='auto';panel.classList.add('anchored-panel');panel._anchorControl=control;const frame=panelFrame(panelViewport());Object.assign(panel.style,{margin:'0',bottom:'auto',left:frame.left+'px',top:frame.top+'px',width:frame.width+'px',maxHeight:frame.height+'px'});panel.showPopover();panel.scrollTop=0;positionPanel(panel,control);}
function repositionPanels(){for(const panel of document.querySelectorAll('.anchored-panel:popover-open'))if(panel._anchorControl?.isConnected)positionPanel(panel,panel._anchorControl);}
window.addEventListener('resize',repositionPanels);window.visualViewport?.addEventListener('resize',repositionPanels);window.visualViewport?.addEventListener('scroll',repositionPanels);

$('settings').onclick=run(async()=>{renderUsage();anchorPanel($('settings-dialog'),$('settings'));const prefs=await api('/api/coordinator/reporting');$('report-enabled').checked=prefs.enabled;$('report-interval').value=prefs.intervalMinutes;$('report-stale').value=prefs.staleAfterMinutes;});
document.querySelector('#settings-dialog .close').onclick=e=>{e.preventDefault();$('settings-dialog').hidePopover();};

const transcriptNode=$('transcript'),readerFrame=el('div','transcript-frame');transcriptNode.replaceWith(readerFrame);readerFrame.append(transcriptNode,$('message-loading'));

const startupMark=document.querySelector('#startup>img');if(startupMark)startupMark.replaceWith(animatedSymbol('nextcomp',{size:56}));

const reviewToggle=$('review-toggle');
review=new ReviewPane({app:$('app'),requests:$('requests'),toggle:reviewToggle,create:el,button,open,
 reply:note=>{$('draft').value=`About this result: “${String(note.text||'').slice(0,500)}”\n\n${$('draft').value}`;saveDraft();$('composer').classList.add('keyboard-open');$('draft').focus();},
 jump:note=>{const row=[...$('transcript').children].find(n=>n._row&&(n._row.id===note.id||n._row.itemId===note.id||n._row.id.endsWith('/'+note.id)));if(row){follow=false;row.scrollIntoView({block:'center',behavior:'auto'});row.classList.add('result-located');setTimeout(()=>row.classList.remove('result-located'),2000);}else error(Error('Original result is outside the loaded history. The saved result remains available here.'));},
 remove:async note=>{const selected=scope,key=selected+'/'+note.id;if(removingNotes.has(key))return;removingNotes.add(key);try{const result=await api('/api/threads/'+encodeURIComponent(selected)+'/notes/remove',{id:note.id});if(!Array.isArray(result.notes))throw Error('Removal was not confirmed.');notesVersions.set(selected,(notesVersions.get(selected)||0)+1);const page=cache.get(selected);if(page?.snapshot)page.snapshot.notes=result.notes;if(scope===selected){current.notes=result.notes;renderNotes();}}finally{removingNotes.delete(key);}}
});

const inlineMedia=new InlineMedia($('transcript'));

// Canonical rooms mount into the SAME list, reader, review pane and bottom
// composer. They never masquerade as stock thread IDs or borrow stock controls.
const roomControls=el('div','room-controls');roomControls.id='room-controls';roomControls.hidden=true;
function roomSelect(id,label,attribute){const holder=el('label','',label),select=el('select');select.id=id;select.setAttribute('aria-label',label);select.setAttribute(attribute,'');holder.append(select);roomControls.append(holder);return select;}
const roomGateway=roomSelect('room-gateway','Gateway','data-room-gateway');
const roomProjection=roomSelect('room-view','View','data-room-view');
const roomRecipient=roomSelect('room-recipient','Recipient','data-room-recipient');
const roomAudience=roomSelect('room-audience','Audience','data-room-audience');
const roomReply=button('Clear reply target',()=>changeRoomTarget({...targetFor(scope),sourceEventId:null}));roomReply.id='room-source-event';roomControls.append(roomReply);
const roomRoster=el('details','room-roster'),roomRosterTitle=el('summary',''),roomRosterBody=el('div','');
roomRoster.id='room-agent-roster';roomRosterBody.style.whiteSpace='pre-line';roomRosterBody.style.maxHeight='260px';roomRosterBody.style.overflow='auto';
roomRoster.append(roomRosterTitle,roomRosterBody);roomControls.append(roomRoster);
$('composer').prepend(roomControls);
function options(select,values,value){const signature=JSON.stringify(values);if(select.dataset.options!==signature){select.replaceChildren(...values.map(([id,label])=>{const option=el('option','',label);option.value=id;return option;}));select.dataset.options=signature;}select.value=value;}
function renderRoomHeader(){
 const e=rooms.entry(scope),route=rooms.route(scope,{requireAvailable:false}),target=targetFor(scope),detail=current,lead=roomLead(e.room);
 roomControls.hidden=false;$('main').dataset.roomId=e.roomId;
 roomRosterTitle.textContent=roomOriginSummary(detail,{available:!detail?.detailError});
 roomRosterBody.textContent=[...roomOriginRows(detail,{available:!detail?.detailError}),...(detail?.actors||[]).filter(a=>a.can_read===true).map(a=>
   roomActorLabel(a)+(a.participation==='retained-source-owner'?' · Retained original-source owner (not public fanout)':a.identity?.originRoomId===e.roomId?' · Origin here':' · Existing/visiting participant'))].join('\n');
 options(roomGateway,e.routes.map(r=>[r.environment,r.name+(r.available?'':' · Unavailable')]),route.environment);
 options(roomProjection,lead?[['all','Lead + activity'],['conversation','Lead conversation'],['activity','Activity'],['coordination','Team'],['archive','Earlier room']]:[['all','Conversation + activity'],['conversation','Conversation'],['activity','Activity']],roomView);
 options(roomRecipient,lead?[['','Lead · '+lead.name],[lead.agent,lead.name],...(target.recipientId&&target.recipientId!==lead.agent?[[target.recipientId,'Earlier recipient · saved only']]:[])]:[['','All room agents'],...currentRoomActors(detail?.actors).filter(a=>a.kind==='agent'||a.id===target.recipientId).map(a=>[a.id,roomActorLabel(a)]),...(detail?.actors||[]).filter(a=>a.participation==='retained-source-owner'&&a.id===target.recipientId).map(a=>[a.id,roomActorLabel(a)+' · Earlier participant'])],target.recipientId||'');
 options(roomAudience,[['room','Public room'],['direct','Private to recipient']],target.audience);
 roomReply.hidden=!target.sourceEventId;roomReply.title=target.sourceEventId||'';
 setLabel($('title'),e.room.name||'Hollywood room');setLabel($('context-label'),'HOLLYWOOD ROOM');
 const readRoute=e.routes.find(r=>r.environment===e.readEnvironment&&r.available)||route,state=roomState({...e.room,...(detail?.nativeState?{nativeState:detail.nativeState}:{}),...(detail?.roomAgents?{roomAgents:detail.roomAgents}:{})},{available:readRoute.available&&!detail?.detailError});
 setLabel($('session-state'),state.label+(readRoute.environment!==route.environment?' · Reading via '+readRoute.name+'; saved sends retain '+route.name:'')+(cache.get(scope)?.catchingUp?' · Catching up missed events':'')+(detail?.detailError?' · '+detail.detailError:''));
 $('session-menu').hidden=true;$('parent').hidden=true;$('coordinator').classList.remove('active');$('send-mode').hidden=true;
 const enabled=(!lead||!['coordination','archive'].includes(roomView))&&(!lead||!target.recipientId||target.recipientId===lead.agent)&&route.available&&!detail?.detailError&&detail?.permissions?.can_post===true&&
  (target.recipientId===null||(detail.actors||[]).some(a=>a.id===target.recipientId&&a.kind==='agent'))&&(target.audience!=='direct'||!!target.recipientId);
 $('draft').disabled=false; // An outage must not prevent saving a local draft.
 $('send').disabled=!enabled||!!deliveryFlight||!!pending;
 $('mic').disabled=!voice.recording&&(!enabled||!voice.supported());
 $('draft').placeholder=lead?'Message the lead…':target.audience==='direct'?'Message the selected agent privately…':'Message this room…';
 setLabel($('draft-note'),lead?'Only the lead receives your input. Team contains internal coordination.':target.audience==='room'?'Typed and spoken room input reaches the current room agents.':'Private input to the selected recipient.');
}
function changeRoomTarget(target){
 if(!isRoom(scope))throw Error('Open the original room.');saveDraft();
 roomTargets.set(scope,target);write('roomTarget.'+deviceId+'.'+scope,target);
 openedDraftKey=draftKey();$('draft').value=read(openedDraftKey,'');renderHeader();
}
roomRecipient.onchange=run(()=>changeRoomTarget({...targetFor(scope),recipientId:roomRecipient.value||null,sourceEventId:null}));
roomAudience.onchange=run(()=>{if(roomAudience.value==='direct'&&!targetFor(scope).recipientId){renderHeader();throw Error('Choose an explicit recipient before private input.');}changeRoomTarget({...targetFor(scope),audience:roomAudience.value,sourceEventId:null});});
roomGateway.onchange=run(async()=>{saveDraft();saveView();rooms.select(scope,roomGateway.value);current=null;openedDraftKey=draftKey();$('draft').value=read(openedDraftKey,'');renderHeader();renderUsage();void refreshAccountUsage();await refreshConversation();});
roomProjection.onchange=run(async()=>{saveView();roomView=roomProjection.value;write('roomView.'+deviceId+'.'+scope,roomView);const history=rooms.history(scope,roomView),view=readerViews.get(readerKey());follow=view?.follow??true;cache.set(scope,{rows:history.rows(),hasEarlier:history.olderCursor!==null,before:history.olderCursor,snapshot:current});renderHeader();renderTranscript();restoreView(view);await refreshConversation();});
function roomVoiceOptions(){
 if(!isRoom(scope)){if(scope==='coordinator'&&status?.backend==='lax')throw Error('Choose a room before recording.');return {};}
 const e=rooms.entry(scope),route=rooms.route(scope),target={...targetFor(scope)};assertLeadTarget(e.room,target);
 if(target.audience==='direct'&&!target.recipientId)throw Error('Choose an explicit direct recipient.');
 if(current?.detailError||!current?.permissions?.can_request)throw Error('Room voice is unavailable for this human binding.');
 return {destination:{room:{backend:'lax',bindingId:route.binding.bindingId,authorityId:route.binding.authorityId,roomId:e.roomId,...(target.recipientId?{recipientId:target.recipientId}:{}),audience:target.audience,...(target.sourceEventId?{sourceEventId:target.sourceEventId}:{})}},transport:{environment:route.environment}};
}
function decisionKey(route,id){return 'roomDecision.'+JSON.stringify([deviceId,route.environment,route.binding.bindingId,route.binding.principalId,id]);}
const decisionFlights=new Map();
function renderRoomAttention(){
 const holder=$('requests'),route=structuredClone(rooms.route(scope,{requireAvailable:false})),items=current?.roomAttention||[];
 const signature=JSON.stringify([scope,route.environment,current?.attentionError,items,items.map(v=>read(decisionKey(route,v.attention.id),null))]);
 if(holder.dataset.roomSignature===signature)return;holder.dataset.roomSignature=signature;holder.replaceChildren();
 if(current?.attentionError)holder.append(el('p','muted','Questions unavailable: '+current.attentionError));
 for(const original of items){
  const a=original.attention,key=decisionKey(route,a.id),saved=read(key,null),answerKey=key+'.answers';
  const box=el('article','request-card');box.dataset.roomAttentionId=a.id;
  box.append(el('strong','',a.method==='hollywood/question'||a.method==='item/tool/requestUserInput'?'Native question':'Native approval'),el('p','',a.state));
  const inputs=[],answers=read(answerKey,{}),request=a.request||{};
  const question=['hollywood/question','item/tool/requestUserInput'].includes(a.method);
  const statusNode=el('p','request-status',saved?'Saved original response · '+saved.state:!canAnswer(original)?'Original callback unavailable or expired.':'');statusNode.setAttribute('role','status');
  if(question)for(const q of request.questions||[]){const label=el('label','',q.question),input=el('input');input.dataset.question=q.id;input.setAttribute('aria-label',q.question);input.value=answers[q.id]||'';input.oninput=()=>{answers[q.id]=input.value;write(answerKey,answers);};label.append(input);box.append(label);inputs.push(input);
   for(const option of q.options||[]){const choice=button(option.label,()=>{input.value=option.label;input.oninput();});choice.disabled=!!saved||!canAnswer(original);box.append(choice);}
  }else box.append(el('pre','',request.command||JSON.stringify(request.changes||request)));
  const performResponse=async(response,inspect=false)=>{
   let intent=read(key,null);
   try{
    if(!intent){
     if(!route.available)throw Error('Original response gateway is unavailable. Saved answer retained.');
     const fresh=await rooms.call(route,'attention/'+a.id);
     if(!jsonEqual(original.scope,fresh.scope)||!canAnswer(fresh))throw Error('Original question expired or native scope changed. Saved answers retained.');
     intent={route,body:{operationId:'web-attention:'+crypto.randomUUID(),scope:structuredClone(original.scope),response},state:'prepared'};write(key,intent);
    }
    if(!bindingMatches(route.binding,intent.route.binding))throw Error('Original response belongs to another binding.');
    const readOnly=inspect||intent.state!=='prepared';
    if(!readOnly){intent={...intent,state:'dispatched'};write(key,intent);}
    const receipt=await rooms.call(intent.route,readOnly?'attention/'+a.id+'/commands/'+encodeURIComponent(intent.body.operationId):'attention/'+a.id+'/response',readOnly?undefined:intent.body);
    if(!rooms.attentionAccepted(intent,receipt))throw Error('Original response receipt is unconfirmed.');
    write(key+'.receipt',{intent,receipt});write(key,null);write(answerKey,{});statusNode.textContent='Response accepted; native consumption is separate.';
    await refreshConversation();
   }catch(e){if(intent)write(key,{...intent,state:'unknown',error:e.message});statusNode.textContent=e.message;holder.dataset.roomSignature='';renderRequests();throw e;}
  };
  const submit=(response,inspect=false)=>{if(decisionFlights.has(key))return decisionFlights.get(key);const job=performResponse(response,inspect).finally(()=>decisionFlights.delete(key));decisionFlights.set(key,job);return job;};
  if(saved)box.append(button('Check original response',()=>submit(null,true)));
  else if(question){const send=button('Submit answers',()=>{const response={answers:Object.fromEntries(inputs.map(i=>[i.dataset.question,{answers:[i.value]}]))};if(inputs.some(i=>!i.value.trim()))throw Error('Answer each original question.');return submit(response);});send.disabled=!canAnswer(original)||!!current?.attentionError;box.append(send);}
  else if(['item/commandExecution/requestApproval','item/fileChange/requestApproval','hollywood/approval'].includes(a.method))for(const decision of ['accept','decline']){const control=button(decision==='accept'?'Approve':'Decline',()=>submit({decision}));control.disabled=!canAnswer(original)||!!current?.attentionError;box.append(control);}
  else box.append(el('p','muted','This native request type is unavailable in the web client.'));
  for(const input of inputs)input.disabled=!!saved||!canAnswer(original);
  box.append(statusNode);holder.append(box);
 }
 // A callback leaving the pending list must not hide its uncertain response.
 for(const storageKey of Object.keys(localStorage).filter(k=>k.startsWith('nextcomp.web.v1.roomDecision.'))){
  let intent;try{intent=JSON.parse(localStorage.getItem(storageKey));}catch{continue;}
  if(!intent?.body?.scope||!intent.route||!bindingMatches(route.binding,intent.route.binding)||intent.route.environment!==route.environment||intent.body.scope.roomId!==rooms.entry(scope).roomId||items.some(v=>v.attention.id===intent.body.scope.attentionId))continue;
  const key=storageKey.slice('nextcomp.web.v1.'.length),box=el('article','request-card','Saved native response · '+intent.state);
  box.append(button('Check original response',async()=>{const receipt=await rooms.call(intent.route,'attention/'+intent.body.scope.attentionId+'/commands/'+encodeURIComponent(intent.body.operationId));if(!rooms.attentionAccepted(intent,receipt))throw Error('Original response remains unconfirmed.');write(key+'.receipt',{intent,receipt});write(key,null);holder.dataset.roomSignature='';renderRequests();}));holder.append(box);
 }
}

const nativeSettingsPanel=new NativeSettingsPanel({api,getScope:()=>scope,openThread:id=>open(id)});
const nativeSettingsButton=button('Codex settings & agents',()=>{closeControls();return nativeSettingsPanel.open();});
$('controls-dialog').append(nativeSettingsButton);
$('settings-dialog').append(button('Codex settings & tools',()=>nativeSettingsPanel.open()));
const serviceTierControl=addServiceTierControl({holder:$('model').parentElement.parentElement,modelSelect:$('model'),getModels:()=>models,getStored:()=>current?.turnSettings});
$('effort').parentElement.after($('service-tier').parentElement);
function executionApi(origin){return executionApiFor(api,origin);}
function executionCurrent(origin){return ()=>scope===origin;}
const terminalsControl=button('Background terminals',()=>{const origin=scope;closeControls();openTerminals({threadId:origin,api:executionApi(origin),isCurrent:executionCurrent(origin),create:el,button});});
$('controls-dialog').append(terminalsControl);

$('download-ux').onclick=()=>{
 const report={...ux.snapshot(),exportedAt:new Date().toISOString(),exportContext:{viewportWidth:window.innerWidth,viewportHeight:window.innerHeight,devicePixelRatio:window.devicePixelRatio,loadedClientBuild:'unavailable',evidence:'Export-time viewport only; not per-sample presentation evidence.'}};
 const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)+'\n'],{type:'application/json'}));
 const link=document.createElement('a');link.href=url;link.download='nextcomp-ux-report.json';link.click();
 requestAnimationFrame(()=>URL.revokeObjectURL(url));
};
