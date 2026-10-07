import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
import {VoiceController} from '../server/voice-controller.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
class FixtureCodex extends EventEmitter {
  constructor() { super(); this.calls=[]; this.sequence=0; }
  async connect() {}
  async call(method,params) {
    this.calls.push({method,params});
    if(method==='thread/start') return {thread:{id:`controller_${++this.sequence}`}};
    if(method==='turn/start') return {turn:{id:`fixture_turn_${this.sequence}`}};
    return {};
  }
  answer() {}
}
function fixture(t) {
  const db=new DatabaseSync(':memory:'), codex=new FixtureCodex(), calls=[];
  const threads=new Map([
    ['dns_work',{id:'dns_work',name:'Untitled task',preview:'Private DNS on Android',cwd:'/fixture/network',status:{type:'active'},updatedAt:200}],
    ['career_work',{id:'career_work',name:'Untitled task',preview:'Google referral strongest career matches',cwd:'/fixture/jobs',status:{type:'idle'},updatedAt:100}],
    ['italian_work',{id:'italian_work',name:'Renamed project',preview:'Inline vocabulary and grammatical agreement',cwd:'/fixture/language',status:{type:'active'},updatedAt:50}]
  ]);
  const histories=new Map([
    ['dns_work',{user:'Keep Tailscale connected while repairing Android DNS.',assistant:'Resolver failures occur only with the VPN connected.'}],
    ['career_work',{user:'Rank Google Careers roles for strongest referral chances. No applications.',assistant:'Three software roles have stronger profile fit.'}],
    ['italian_work',{user:'Replace Italian phrases inline, without duplicated translations.',assistant:'Agreement must follow the contextual noun phrase.'}]
  ]);
  const api=async(path,body)=>{
    calls.push({path,body});
    if(path.startsWith('/api/threads?') && !body) return {threads:[...threads.values()],refreshPending:false};
    if(path==='/api/threads' && !body) return {threads:[...threads.values()],refreshPending:false};
    if(path.startsWith('/api/threads/') && path.endsWith('?view=timeline') && !body) {
      const id=path.split('/')[3].split('?')[0],thread=threads.get(id),content=histories.get(id);
      if(!thread) throw Object.assign(Error('Thread no longer exists.'),{status:404});
      return {thread,timeline:{hasEarlier:true,rows:[
        {kind:'user',type:'userMessage',text:content.user,turnId:'fixture_history'},
        {kind:'message',type:'agentMessage',phase:'commentary',text:content.assistant,turnId:'fixture_history'}]},notes:[],pending:[],outgoing:[]};
    }
    if(path.endsWith('/reply') && body) {
      const id=path.split('/')[3];
      assert.ok(threads.has(id),'Only synthetic session mutations are permitted.');
      return {id:body.id,thread_id:id,state:body.mode==='queue'?'queued':'accepted'};
    }
    if(path==='/api/threads' && body) {
      threads.set('created_work',{id:'created_work',name:'New app task',cwd:body.cwd,status:{type:'pending'},updatedAt:300});
      histories.set('created_work',{user:body.prompt,assistant:''});
      return {id:body.id,state:'started',thread_id:'created_work'};
    }
    throw Error(`Unexpected fixture API operation ${path}`);
  };
  const controller=new VoiceController({db,codex,api,cwd:'/fixture',host:'synthetic',transcribe:async()=>''});
  t.after(()=>{
    for(const active of controller.active.values()) clearTimeout(active.timer);
    controller.active.clear();db.close();
  });
  const active=(device='phone',id='fixture_input')=>({device,id,text:'',thread:controller.session(device)?.thread_id});
  const record=(device='phone',id='fixture_input',transcript='Continue that work')=>{
    db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES(?,?,?,'thinking',?,?)")
      .run(device,id,'fixture',transcript,Date.now());
    return active(device,id);
  };
  return {db,codex,calls,threads,histories,controller,active,record};
}

// These integration checks deliberately drive tool choices themselves. They
// qualify controller enforcement and context, not real-model semantic accuracy.

test('coordinator discovery supplies history evidence for generic and renamed titles', async t => {
  const {controller,record,calls}=fixture(t);
  await controller.ensure('phone');
  const result=await controller.control(record(),{operation:'discover',arguments:{query:'phone resolution broken with VPN',limit:3}});
  assert.equal(result.sessions.length,3);
  const dns=result.sessions.find(session=>session.id==='dns_work');
  const career=result.sessions.find(session=>session.id==='career_work');
  assert.equal(dns.name,career.name);
  assert.match(dns.latestUser,/Tailscale/);
  assert.match(career.latestUser,/referral/);
  assert.match(result.sessions.find(session=>session.id==='italian_work').latestAssistant,/Agreement/);
  assert.ok(calls.some(call=>call.path.includes('view=coordinator')));
  assert.equal(calls.filter(call=>call.body).length,0);
});

test('fresh target validation precedes submitting to an existing session and preserves queue and steer', async t => {
  const {controller,record,calls}=fixture(t);
  await controller.ensure('phone');
  const active=record('phone','fixture_input','Queue a DNS check and steer Italian immersion to preserve one inline flow.');
  await controller.control(active,{operation:'reply',arguments:{threadId:'dns_work',text:'Check resolver routing first',mode:'queue'}});
  await controller.control(active,{operation:'reply',arguments:{threadId:'italian_work',text:'Preserve a single inline flow',mode:'steer'}});
  const posts=calls.filter(call=>call.body);
  assert.equal(posts.length,2);
  assert.equal(posts[0].path,'/api/threads/dns_work/reply');
  assert.equal(posts[0].body.mode,'queue');
  assert.equal(posts[1].path,'/api/threads/italian_work/reply');
  assert.equal(posts[1].body.mode,'steer');
  for(const post of posts) {
    const id=post.path.split('/')[3];
    const index=calls.indexOf(post);
    assert.ok(calls.slice(0,index).some(call=>call.path===`/api/threads/${id}?view=timeline`));
  }
});

test('deleted or wrong-ID target evidence cannot submit work', async t => {
  const {controller,record,calls,threads}=fixture(t);
  await controller.ensure('phone');
  const active=record();
  threads.delete('dns_work');
  await assert.rejects(controller.control(active,{operation:'reply',arguments:{threadId:'dns_work',text:'Repair DNS'}}));
  threads.set('dns_work',{id:'wrong_target',name:'Another task',status:{type:'idle'}});
  await assert.rejects(controller.control(active,{operation:'reply',arguments:{threadId:'dns_work',text:'Repair DNS'}}));
  assert.equal(calls.filter(call=>call.body).length,0);
});

test('explicit direct-session focus routes an ordinary reply without creating another chat', async t => {
  const {controller,record,calls}=fixture(t);
  await controller.ensure('phone');
  await controller.setFocus('phone','career_work',{mode:'direct'});
  await controller.control(record(),{operation:'reply',arguments:{text:'Focus on strongest fit first',mode:'queue'}});
  assert.equal(calls.at(-1).path,'/api/threads/career_work/reply');
  assert.equal(calls.at(-1).body.mode,'queue');
  assert.equal(calls.filter(call=>call.path==='/api/threads' && call.body).length,0);
});

test('discovery is required before creating work rather than matching titles alone', async t => {
  const {controller,record,calls}=fixture(t);
  await controller.ensure('phone');
  const active=record();
  await assert.rejects(controller.control(active,{operation:'create',arguments:{cwd:'/fixture',prompt:'New app task'}}));
  assert.equal(calls.filter(call=>call.body).length,0);
  await controller.control(active,{operation:'discover',arguments:{query:'New app task'}});
  const created=await controller.control(active,{operation:'create',arguments:{cwd:'/fixture',prompt:'New app task',reason:'The discovered work concerns DNS, careers and language; none covers this new app.'}});
  assert.equal(created.state,'started');
  assert.equal(calls.filter(call=>call.path==='/api/threads' && call.body).length,1);
});

test('automatic routes retain independent references without silently selecting the last target', async t => {
  const {controller,record,db,codex}=fixture(t);
  await controller.ensure('phone');
  controller.setFocus('phone',null,{mode:'coordinator'});
  const first=record('phone','first_route','Queue the DNS repair, and send the Italian grammar feedback too.');
  await controller.control(first,{operation:'reply',arguments:{threadId:'dns_work',text:'Check resolver routing',mode:'queue'}});
  await controller.control(first,{operation:'reply',arguments:{threadId:'italian_work',text:'Use contextual noun agreement',mode:'auto'}});
  assert.equal(controller.session('phone').selected,null);
  assert.equal(controller.session('phone').focus,'coordinator');
  const refs=JSON.parse(controller.session('phone').refs);
  assert.deepEqual(refs.map(ref=>ref.threadId),['dns_work','italian_work']);
  assert.deepEqual(refs.map(ref=>ref.state),['queued','submitted']);
  assert.deepEqual(controller.get('phone','first_route').actions.map(action=>action.threadId),['dns_work','italian_work']);
  db.prepare("UPDATE voice_turns SET state='completed' WHERE id='first_route'").run();
  record('phone','follow_up','Continue both, keeping those restrictions.');
  await controller.run('phone','follow_up',null,'Continue both, keeping those restrictions.');
  const input=codex.calls.find(call=>call.method==='turn/start').params.input[0].text;
  const context=JSON.parse(input.split('\n')[0].replace('Current NextComp context: ',''));
  assert.equal(context.selectedSession,null);
  assert.deepEqual(context.references.map(ref=>ref.threadId),['dns_work','italian_work']);
  assert.ok(context.discovery.sessions.some(session=>session.id==='dns_work'));
  assert.ok(context.discovery.sessions.some(session=>session.id==='italian_work'));
  assert.match(input,/Continue both/);
});

test('correction is device-scoped exact prior receipt and does not silently resend accepted work', async t => {
  const {controller,record,db,calls,codex}=fixture(t);
  await controller.ensure('phone');await controller.ensure('other_phone');
  const first=record('phone','prior_request','Send this feedback to DNS.');
  await controller.control(first,{operation:'reply',arguments:{threadId:'dns_work',text:'Use correct resolver routing',mode:'auto'}});
  db.prepare("UPDATE voice_turns SET state='completed' WHERE id='prior_request'").run();
  assert.throws(()=>controller.submitText('other_phone','cross_device','Actually use the Italian task.',{turnId:'prior_request',threadId:'dns_work'}),/does not belong/);
  assert.throws(()=>controller.submitText('phone','wrong_receipt','Actually use the Italian task.',{turnId:'prior_request',threadId:'career_work'}),/does not belong/);
  controller.submitText('phone','correct_target','Actually use the Italian task.',{turnId:'prior_request',threadId:'dns_work'});
  for(let i=0;i<20&&!codex.calls.some(call=>call.method==='turn/start');i++)await tick();
  const input=codex.calls.find(call=>call.method==='turn/start').params.input[0].text;
  const context=JSON.parse(input.split('\n')[0].replace('Current NextComp context: ',''));
  assert.equal(context.correctionOf.threadId,'dns_work');
  assert.equal(context.correctionOf.state,'submitted');
  assert.equal(context.correctionOf.originalRequest,'Send this feedback to DNS.');
  assert.equal(calls.filter(call=>call.body).length,1);
});

test('fresh archived and managed-child targets are surfaced without trying to resume or mutate them', async t => {
  const {controller,record,threads,calls,codex}=fixture(t);
  await controller.ensure('phone');
  threads.get('dns_work').archived=true;
  threads.get('italian_work').managedChild=true;
  const active=record();
  const discovery=await controller.control(active,{operation:'discover',arguments:{threadIds:['dns_work','italian_work']}});
  assert.equal(discovery.sessions.find(session=>session.id==='dns_work').archived,true);
  assert.equal(discovery.sessions.find(session=>session.id==='italian_work').managedChild,true);
  for(const id of ['dns_work','italian_work'])await assert.rejects(controller.control(active,{operation:'reply',arguments:{threadId:id,text:'Continue'}}));
  assert.equal(calls.filter(call=>call.body).length,0);
  assert.equal(codex.calls.filter(call=>call.method==='thread/resume').length,0);
});

test('selected focus survives routing to an explicit different session and creation stays globally unselected by default', async t => {
  const {controller,record}=fixture(t);
  await controller.ensure('phone');
  controller.setFocus('phone','career_work',{mode:'selected'});
  const active=record();
  await controller.control(active,{operation:'reply',arguments:{threadId:'dns_work',text:'Check DNS',mode:'steer'}});
  assert.equal(controller.session('phone').selected,'career_work');
  assert.equal(controller.session('phone').focus,'selected');
  controller.setFocus('phone',null);
  await controller.control(active,{operation:'discover',arguments:{query:'New independent app task'}});
  await controller.control(active,{operation:'create',arguments:{cwd:'/fixture',prompt:'New independent app task',reason:'No discovered session covers this new work.'}});
  assert.equal(controller.session('phone').selected,null);
  assert.equal(controller.session('phone').focus,'coordinator');
});

test('in-flight direct focus does not follow a concurrent UI selection change', async t=>{
 const {controller,record,calls}=fixture(t);await controller.ensure('phone');
 controller.setFocus('phone','career_work',{mode:'direct'});
 const active={...record(),focus:'direct',selected:'career_work'};
 controller.setFocus('phone','dns_work',{mode:'direct'});
 await controller.control(active,{operation:'reply',arguments:{text:'Queue the strongest matches',mode:'queue'}});
 assert.equal(calls.at(-1).path,'/api/threads/career_work/reply');
});

test('read and secondary mutations reject mismatched thread identities',async t=>{
 const {controller,record,threads,calls}=fixture(t);await controller.ensure('phone');
 threads.get('dns_work').id='career_work';const active=record();
 for(const operation of ['read','select','settings','interrupt','rename','archive','watch','queueResume','queueEdit','restore']){
  await assert.rejects(controller.control(active,{operation,arguments:{threadId:'dns_work',replyId:'synthetic_reply'}}),/identity changed/);
 }
 assert.equal(calls.filter(c=>c.body).length,0);
});

test('catch-up retrieval and staged findings remain unread until completed response delivery',async t=>{
 const {controller,record,db}=fixture(t);await controller.ensure('phone');
 const presented=[];controller.catchup={hydrate:()=>({firstCheck:false}),checkedThreadIds:()=>['dns_work'],snapshot:async()=>({sessions:[{threadId:'dns_work',items:[{id:42,seq:42,text:'Resolver fixed'}]}],attention:[]}),presented:(device,items)=>presented.push({device,items})};
 const active=record();const result=await controller.control(active,{operation:'catchup',arguments:{}});
 assert.equal(result.sessions[0].items[0].text,'Resolver fixed');assert.equal(presented.length,0);
 await controller.control(active,{operation:'presentCatchup',arguments:{items:[{threadId:'dns_work',id:42,seq:42}]}});
 assert.equal(presented.length,0);assert.throws(()=>controller.presented('phone',active.id),/not complete/);
 db.prepare("UPDATE voice_turns SET state='completed' WHERE id=?").run(active.id);
 controller.presented('phone',active.id);assert.equal(presented.length,1);assert.equal(presented[0].items.length,1);
});

test('periodic reporting reads and changes only the captured device preferences without loopback API or model calls',async t=>{
 const {controller,record,calls,codex}=fixture(t);await controller.ensure('phone');await controller.ensure('other_phone');
 const preferences=new Map(),access=[];
 const settings=device=>({...({enabled:false,intervalMinutes:30,staleAfterMinutes:120}),...preferences.get(device)});
 controller.reports={settings:device=>{access.push({operation:'settings',device});return settings(device);},configure:(device,value)=>{access.push({operation:'configure',device,value});preferences.set(device,{...settings(device),...value});return settings(device);}};
 const active=record(),runtimeCalls=codex.calls.length;
 const before=await controller.control(active,{operation:'reportSettings',arguments:{}});
 assert.deepEqual(before,{enabled:false,intervalMinutes:30,staleAfterMinutes:120});
 const changed=await controller.control(active,{operation:'reportSettings',arguments:{enabled:true,intervalMinutes:45}});
 assert.deepEqual(changed,{enabled:true,intervalMinutes:45,staleAfterMinutes:120});
 assert.deepEqual(settings('other_phone'),{enabled:false,intervalMinutes:30,staleAfterMinutes:120});
 await controller.control(active,{operation:'reportSettings',arguments:{enabled:false}});
 assert.equal(settings('phone').enabled,false);
 assert.ok(access.every(call=>call.device==='phone'));
 assert.equal(calls.length,0);assert.equal(codex.calls.length,runtimeCalls);
});

test('periodic report settings reject cross-device keys and malformed preferences before changing them',async t=>{
 const {controller,record,calls}=fixture(t);await controller.ensure('phone');let changes=0;
 controller.reports={settings:()=>({enabled:false}),configure:()=>{changes++;return {enabled:true};}};
 const active=record();
 for(const arguments_ of [{device:'other_phone',enabled:true},{threadId:'dns_work',enabled:true},{enabled:'yes'},{enabled:null},{intervalMinutes:1.5},{intervalMinutes:0},{staleAfterMinutes:-1},{staleAfterMinutes:'120'}])await assert.rejects(controller.control(active,{operation:'reportSettings',arguments:arguments_}));
 assert.equal(changes,0);assert.equal(calls.length,0);
});

test('missing report service and service validation failures never claim settings changed',async t=>{
 const {controller,record,calls}=fixture(t);await controller.ensure('phone');const active=record();
 await assert.rejects(controller.control(active,{operation:'reportSettings',arguments:{enabled:true}}),error=>error.status===503);
 controller.reports={configure:()=>{throw Object.assign(Error('Choose an interval from five minutes through one day.'),{status:400});}};
 await assert.rejects(controller.control(active,{operation:'reportSettings',arguments:{intervalMinutes:4}}),/five minutes/);
 assert.equal(calls.length,0);assert.equal(controller.get('phone',active.id).actions.length,0);
});

test('recent reports enter the requesting device context separately from last-command references and direct focus',async t=>{
 const {controller,record,codex}=fixture(t);await controller.ensure('phone');controller.setFocus('phone','career_work',{mode:'direct'});
 const access=[];controller.reports={context:(device,options)=>{access.push({device,options});return [{origin:'report',at:1234,response:'DNS delivery is blocked by VPN resolver configuration.',routes:[{threadId:'dns_work',operation:'read'}]}];}};
 const prior=JSON.stringify([{threadId:'career_work',request:'Compare referral salary fit',mode:'queue'}]);
 controller.db.prepare('UPDATE voice_sessions SET refs=? WHERE device=?').run(prior,'phone');
 record('phone','report_followup','Explain the blocker mentioned in the recent report.');
 await controller.run('phone','report_followup',null,'Explain the blocker mentioned in the recent report.');
 const input=codex.calls.find(call=>call.method==='turn/start').params.input[0].text;
 const context=JSON.parse(input.split('\n')[0].replace('Current NextComp context: ',''));
 assert.deepEqual(access,[{device:'phone',options:{limit:3}}]);
 assert.equal(context.recentReports[0].origin,'report');assert.match(context.recentReports[0].response,/VPN resolver/);
 assert.equal(context.references[0].threadId,'career_work');assert.equal(context.selectedSession,'career_work');
 assert.equal(controller.session('phone').refs,prior);assert.equal(controller.session('phone').selected,'career_work');
});
