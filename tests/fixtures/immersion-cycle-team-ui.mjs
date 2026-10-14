// Synthetic native UI qualification only: no model, push or audio provider.
import express from 'express';
import http from 'node:http';
import {WebSocketServer} from 'ws';
const app=express();app.use(express.json());const server=http.createServer(app),ws=new WebSocketServer({server});
import {readFileSync} from 'node:fs';
let cyclePlan=JSON.parse(readFileSync(new URL('./immersion-cycle-plan.json',import.meta.url),'utf8'));
const teamRequests=[];let delayTeamHistory=false;
let roomHistoryMode='normal';
app.post('/fixture/team/condensed-history',(_,r)=>{roomHistoryMode='condensed';r.json({ok:true});});
app.post('/fixture/team/long-history',(_,r)=>{roomHistoryMode='long';r.json({ok:true});});
function roomHistoryRows(){
 const message=(id,author,body)=>({id,kind:'message',author,body,visibility:'room',task:'lobby',created_at:Date.now()});
 if(roomHistoryMode==='long')return Array.from({length:24},(_,i)=>message(300+i,'Violet',i===23?'LATEST ROOM MESSAGE':'Earlier room message '+i+' with enough text to occupy a real reading passage.'));
 const artifact=(id,itemId,status,body)=>({id,kind:'artifact',author:'Violet',body,visibility:'room',task:'lobby',data:{thread:'independent-Violet',itemId,status,completed:status!=='inProgress'},created_at:Date.now()});
 return [message(300,'Violet','First Violet message'),message(301,'Violet','Second Violet message'),
  artifact(302,'exec-a','inProgress','Running synthetic command A'),artifact(303,'exec-a','completed','Synthetic command A completed'),
  artifact(304,'exec-b','failed','Synthetic command B failed'),message(305,'Violet','Public answer outside the runs'),
  message(306,'Amber','First Amber message'),message(307,'Amber','Second Amber message'),message(308,'Violet','Violet returns after Amber')];
}
const requests=[];let failRead=false;let outgoing=[];let notes=[{id:'source-answer',text:'Use 3840 × 2160 pixels for the final image.',question:'What size should the final image be?',manual:0,at:Date.now()}];const thread={id:'alpha23-task',name:'Synthetic recovery study',cwd:'/synthetic',status:{type:'idle'},updatedAt:Date.now()/1000,preview:'A meaningful answer beside a failed delivery.',watched:true};
const timeline={rows:[{id:'source-question',turnId:'synthetic',kind:'user',role:'user',text:'What size should the final image be?'},{id:'synthetic/source-answer',itemId:'source-answer',turnId:'synthetic',phase:'final_answer',kind:'message',role:'assistant',text:'Use 3840 × 2160 pixels for the final image.'},{id:'source-progress',turnId:'synthetic',kind:'message',phase:'commentary',role:'assistant',text:'The lighting pass is ready for review.'}],hasEarlier:false};
function emit(state='queued',id='fixture-update'){for(const s of ws.clients)s.send(JSON.stringify({type:'reply',threadId:thread.id,id,state}));}
app.get('/fixture/state',(_,r)=>r.json({requests,outgoing}));
app.post('/fixture/set',(q,r)=>{requests.length=0;if(q.body.immersionPlan)cyclePlan=q.body.immersionPlan;if(Array.isArray(q.body.outgoing))outgoing=q.body.outgoing;if(typeof q.body.failRead==='boolean')failRead=q.body.failRead;if(q.body.notes)notes=q.body.notes;if(typeof q.body.answerText==='string')timeline.rows[1].text=q.body.answerText;emit();r.json({ok:true});});
app.get('/health',(_,r)=>r.json({ok:true,codex:true}));app.get('/api/status',(_,r)=>r.json({connected:true,deviceId:'alpha24-fixture',host:'Synthetic alpha24',local:true,usage:{state:'available',updatedAt:Date.now(),weekly:{remainingPercent:68,resetsAt:Math.floor(Date.now()/1000)+172800}}}));
app.get('/api/threads',(_,r)=>r.json({threads:[thread]}));
app.get('/api/threads/:id',(_,r)=>failRead?r.status(503).json({error:'Synthetic Codex history unavailable after acknowledged action'}):r.json({thread:{...thread,turns:[]},timeline,revision:1,notes,pending:[],notifications:[],outgoing,watched:true}));
app.post('/api/threads/:thread/replies/:id',(q,r)=>{const row=outgoing.find(x=>x.id===q.params.id);requests.push({id:q.params.id,body:q.body,authenticated:q.headers.authorization==='Bearer synthetic-alpha24-only'});if(row?.state==='unknown'&&q.body.action==='retry'&&q.body.confirmUnknown!==true)return r.status(409).json({error:'Explicit confirmation required'});if(row?.state==='accepted')return r.status(409).json({error:'Already accepted'});if(q.body.action==='remove'){if(row)row.state='cancelled';outgoing=outgoing.filter(x=>x.id!==q.params.id);}else if(q.body.action==='retry'&&row)row.state='accepted';else if(q.body.action==='edit'&&row)row.text=q.body.text;emit(q.body.action==='remove'?'cancelled':row?.state,q.params.id);r.json({id:q.params.id,state:q.body.action==='retry'?'queued':row?.state});});
app.post('/api/threads/:id/notes/remove',(q,r)=>{requests.push({noteRemove:q.body.id});notes=notes.filter(n=>n.id!==q.body.id);r.json({notes});});
app.post('/api/threads/:id/reply',(q,r)=>{requests.push({newReply:q.body});r.json({state:'accepted'});});
for(const p of ['notifications','attention'])app.get('/api/'+p,(_,r)=>r.json({notifications:[]}));app.get('/api/activity',(_,r)=>r.json({items:[]}));app.get('/api/projects',(_,r)=>r.json({projects:[]}));app.get('/api/immersion',(_,r)=>r.json({enabled:true,density:'strong',translations:[cyclePlan]}));app.post('/api/immersion',(_,r)=>r.json({enabled:true,density:'strong',translations:[cyclePlan]}));app.post('/api/immersion/translate',(q,r)=>r.json({enabled:true,density:'strong',translations:(q.body.sources||[]).filter(x=>x.text===cyclePlan.original).map(x=>({...cyclePlan,id:x.id}))}));
app.get('/guide',(_,r)=>r.type('html').send('<title>Synthetic guide</title><h1>Synthetic local guide</h1>'));
app.get('/api/backends',(_,r)=>r.json({backends:[{id:'codex',configured:true,available:true},{id:'losangelex',configured:true,available:true,environmentId:'synthetic-alpha24'}]}));
app.get('/api/losangelex/teams',(_,r)=>r.json({teams:[{id:'studio',name:'Synthetic studio',lobby:'lobby',coordinator:'Violet'}],next_cursor:null}));
app.get('/api/losangelex/teams/:team/overview',(_,r)=>r.json({team:{Violet:{},Amber:{}},tasks:[{id:'lobby',title:'Lobby'},{id:'texture',title:'Texture study'}],lobby:'lobby',agents:[]}));
let failNextMembership=false;
app.post('/fixture/team/fail-next-membership',(_,r)=>{failNextMembership=true;r.json({ok:true});});
const roomIds={lobby:'11111111-1111-4111-8111-111111111111',texture:'22222222-2222-4222-8222-222222222222'};
const roomActors=[{id:'33333333-3333-4333-8333-333333333333',agent:'Violet',role:'Coordinate'},{id:'44444444-4444-4444-8444-444444444444',agent:'Amber',role:'Review'}];
const roomMembership={lobby:new Set([roomActors[0].id]),texture:new Set([roomActors[0].id])};
let fixtureLobbyTitle='Shared title';
const roomRow=task=>({id:roomIds[task],task,title:task==='lobby'?fixtureLobbyTitle:'Shared title',purpose:task==='lobby'?'Coordination':'Texture work',members:roomActors.filter(a=>roomMembership[task].has(a.id)),contextIsolation:'room-local',membershipGrantsExecution:false});
app.post('/fixture/team/live-room-change',(_,r)=>{fixtureLobbyTitle='Updated lobby';roomMembership.lobby.delete(roomActors[1].id);roomMembership.texture.add(roomActors[1].id);r.json({ok:true});});
app.get('/api/losangelex/teams/:team/rooms',(_,r)=>r.json({authorityId:'fixture-authority',rooms:['lobby','texture'].map(roomRow),next_cursor:null}));
app.get('/api/losangelex/teams/:team/actors',(_,r)=>r.json({actors:roomActors,next_cursor:null}));
app.put('/api/losangelex/teams/:team/rooms/:room/members/:actor',(q,r)=>{
 const task=Object.keys(roomIds).find(task=>roomIds[task]===q.params.room);
 if(!task||!roomActors.some(a=>a.id===q.params.actor))return r.status(400).json({error:'Unknown membership'});
 roomMembership[task].add(q.params.actor);teamRequests.push({method:'membership',task,actor:q.params.actor});if(failNextMembership){failNextMembership=false;return r.status(503).json({error:'Synthetic response lost after membership persisted'});}return r.json(roomRow(task));
});
app.get('/api/losangelex/teams/:team/attention',(_,r)=>r.json({data:[]}));
app.get('/api/losangelex/teams/:team/history',(q,r)=>{const scope=q.query.visibility||'room',task=q.query.task||'lobby';const own={id:201,kind:'message',author:'you',body:`Your own direct message stays with ${scope.startsWith('direct:')?scope.slice(7):'the room'}.`,visibility:scope,task,created_at:Date.now()};const msg={id:202,kind:'message',author:scope.startsWith('direct:')?scope.slice(7):'Violet',body:`Synthetic ${scope} history for ${task}.`,visibility:scope,task,created_at:Date.now()};teamRequests.push({method:'history',scope,task});const aggregate={id:203,kind:'message',author:'Amber',body:'Independent Amber result. [Shared artifact](http://127.0.0.1:18880/guide)',visibility:'room',task,created_at:Date.now()};const data=scope==='room'&&task==='lobby'&&roomHistoryMode!=='normal'?roomHistoryRows():scope.startsWith('direct:')?[own,msg]:roomMembership[task]?.size>1?[msg,aggregate]:[msg];setTimeout(()=>r.json({data,olderCursor:null}),delayTeamHistory&&scope==='direct:Violet'?20000:0);});
let failNextMessage=false;
app.post('/fixture/team/setup-sessions',(_,r)=>{roomHistoryMode='normal';fixtureLobbyTitle='Shared title';teamRequests.length=0;failNextMessage=false;failNextMembership=false;roomActors.forEach(a=>{a.thread='independent-'+a.agent;a.status='idle';a.preview=a.agent+' results';});roomMembership.lobby.clear();roomActors.forEach(a=>roomMembership.lobby.add(a.id));roomMembership.texture.clear();roomMembership.texture.add(roomActors[0].id);r.json({ok:true});});
app.post('/fixture/team/amber-working',(_,r)=>{roomActors[1].status='working';r.json({ok:true});});
app.post('/api/losangelex/teams/:team/control',(q,r)=>{teamRequests.push({method:'control',body:q.body});roomActors.filter(a=>a.agent===q.body.agent).forEach(a=>{a.status='idle';});r.json({ok:true});});
app.post('/fixture/team/fail-next-message',(_,r)=>{failNextMessage=true;r.json({ok:true});});
app.post('/fixture/team/remove-destinations',(_,r)=>{roomMembership.lobby.clear();r.json({ok:true});});
app.post('/api/losangelex/teams/:team/messages',(q,r)=>{teamRequests.push({method:'send',team:q.params.team,body:q.body});if(failNextMessage){failNextMessage=false;return r.status(503).json({error:'Synthetic acceptance reply lost'});}r.json({id:Date.now(),accepted:true});});
app.get('/fixture/team',(_,r)=>r.json({requests:teamRequests}));app.post('/fixture/team',(q,r)=>{if(q.body.reset)teamRequests.length=0;if(typeof q.body.delayHistory==='boolean')delayTeamHistory=q.body.delayHistory;r.json({ok:true});});
const roomVoiceStarts=[],roomVoiceSubmissions=[],roomVoiceReceipts=new Map();
app.post('/fixture/room-voice/reset',(_,r)=>{roomVoiceStarts.length=0;roomVoiceSubmissions.length=0;roomVoiceReceipts.clear();r.json({ok:true});});
app.get('/fixture/room-voice',(_,r)=>r.json({starts:roomVoiceStarts,submissions:roomVoiceSubmissions}));
app.post('/api/voice/start',(q,r)=>{roomVoiceStarts.push(q.body);r.json({focus:'room',native:true});});
app.post('/api/voice/text',(q,r)=>{roomVoiceSubmissions.push(q.body);roomVoiceReceipts.set(q.body.turnId,{id:q.body.turnId,state:'unknown',transcript:q.body.text,error:'Synthetic native acceptance is unconfirmed',actions:[]});r.status(503).json({error:'Synthetic lost acceptance reply'});});
app.get('/api/voice/turns/:id',(q,r)=>roomVoiceReceipts.has(q.params.id)?r.json(roomVoiceReceipts.get(q.params.id)):r.status(404).json({error:'No turn'}));
app.get('/api/app/update',(_,r)=>r.json({available:false}));app.use((_,r)=>r.json({ok:true}));ws.on('connection',s=>s.send(JSON.stringify({type:'status',connected:true})));server.listen(19992,'127.0.0.1',()=>console.log('Synthetic alpha24 fixture19992'));
