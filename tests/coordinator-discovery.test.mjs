import test from 'node:test';
import assert from 'node:assert/strict';
import {CoordinatorDiscovery} from '../server/coordinator-discovery.mjs';

const session = (id, name = 'Fix things', preview = '', updatedAt = 1) => ({id, name, preview, updatedAt, status:{type:'idle'}});
const history = (id, user = '', assistant = '', extra = {}) => ({thread:{id}, timeline:{rows:[
  {type:'userMessage', text:user, turnId:'turn_123'},
  {type:'agentMessage', text:assistant, phase:'commentary', turnId:'turn_123'}], hasEarlier:true}, ...extra});
const fake = (threads, histories = {}) => {
  const calls = [];
  const api = async path => {
    calls.push(path);
    if (path.startsWith('/api/threads?')) return {threads};
    const id = path.split('/')[3].split('?')[0];
    if (!(id in histories)) throw Object.assign(Error('not found token=private'), {status:404});
    return histories[id];
  };
  return {api, calls};
};

test('same or misleading titles retrieve public history and preserve latest request amid commentary', async () => {
  const data = fake([session('thread_a', 'Fix things', 'Pixel DNS connectivity'), session('thread_b','Fix things','Career referral ranking')], {
    thread_a:history('thread_a','Keep Tailscale online while repairing DNS','Resolver route confirmed'),
    thread_b:history('thread_b','Use my profile to rank Google roles','Ranking strongest referrals')});
  const result = await new CoordinatorDiscovery(data).discover({query:'repair phone DNS',limit:2});
  assert.equal(result.sessions[0].id, 'thread_a');
  assert.match(result.sessions[0].latestUser, /Tailscale/);
  assert.match(result.sessions[1].latestUser, /Google/);
  assert.equal(result.sessions[0].history.hasEarlier, true);
  assert.equal('score' in result.sessions[0], false);
  assert.equal('target' in result, false);
});

test('renamed titles and old cross-turn references are read even when omitted from live catalog', async () => {
  const data = fake([session('thread_new','New work','Changed project',900)], {
    thread_old:history('thread_old','Research Italian inline grammar','Agreements located', {thread:{id:'thread_old',name:'Renamed linguistic study',parentThreadId:'parent_123',archived:true}}),
    thread_new:history('thread_new','Unrelated','Unrelated')});
  const result = await new CoordinatorDiscovery(data).discover({query:'continue it',threadIds:['thread_old'],limit:1});
  assert.equal(result.sessions[0].id, 'thread_old');
  assert.equal(result.sessions[0].name, 'Renamed linguistic study');
  assert.equal(result.sessions[0].archived, true);
  assert.equal(result.sessions[0].managedChild, true);
  assert.match(result.sessions[0].latestAssistant, /Agreements/);
});

test('retrieval ranks preview and project evidence, while reserving active and recent diversity', async () => {
  const threads = [session('old_match','Untitled','Italian grammar inflection',1),
    {...session('active_work','GPU bake','Rendering scenes',20),status:{type:'active'}},
    session('recent_work','Something else','Email',100), session('title_match','Italian','Nothing relevant',2)];
  const data = fake(threads,Object.fromEntries(threads.map(t => [t.id,history(t.id,t.preview,'Public response')])));
  const result = await new CoordinatorDiscovery(data).discover({query:'Italian grammar',limit:3});
  assert.deepEqual(new Set(result.sessions.map(s => s.id)),new Set(['old_match','title_match','active_work']));
  const broaden = await new CoordinatorDiscovery(data).discover({threadIds:['recent_work'],limit:1});
  assert.equal(broaden.sessions[0].id,'recent_work');
});

test('fresh listing each call distinguishes pending discovery and deleted reads without raw errors', async () => {
  let count = 0;
  const api = async path => {
    if (path.startsWith('/api/threads?')) return {threads:[session(count++ ? 'next_live' : 'stale_id')],refreshPending:count === 1};
    throw Object.assign(Error('password=credential private server socket'),{status:404});
  };
  const discovery = new CoordinatorDiscovery({api,clock:()=>1234});
  const first = await discovery.discover();
  const second = await discovery.discover();
  assert.equal(first.checkedAt,1234);
  assert.equal(first.scope.complete,false);
  assert.equal(first.scope.refreshPending,true);
  assert.equal(first.sessions[0].readError,'not_found');
  assert.equal(second.sessions[0].id,'next_live');
  assert.doesNotMatch(JSON.stringify(first),/credential|socket/);
});

test('whitelisted evidence never returns credentials, reasoning, command output or arbitrary raw properties', async () => {
  const raw = history('safe_thread','token=secret-value Fix account','Bearer secret-token Continue',{
    thread:{id:'safe_thread',name:'Fix',authToken:'private-field',status:{type:'active',credentials:'private-field'}},
    timeline:{rows:[
      {type:'userMessage',text:'api_key=secret-value Check auth'},
      {type:'reasoning',text:'private-reasoning',detail:'private-reasoning'},
      {type:'commandExecution',text:'curl token=secret-value',detail:'private-logs',status:'completed'},
      {type:'mcpToolCall',title:'private-tool-args',detail:'private-logs'},
      {type:'webSearch',text:'Android DNS',detail:'private-logs'},
      {type:'agentMessage',text:'Use sk-abcdefghijklmnop correctly'}]},
    notes:[{text:'password=secret-value Note',question:'Which profile?',secret:'private-field'}],
    pending:[{credentials:'private-field'}],outgoing:[{state:'queued',text:'private-outgoing',token:'private-field'}]});
  const data = fake([{...session('safe_thread'),secret:'private-field'}],{safe_thread:raw});
  const result = await new CoordinatorDiscovery(data).discover();
  const json = JSON.stringify(result);
  assert.doesNotMatch(json,/private-field|private-reasoning|private-logs|private-outgoing|private-tool-args|secret-value|sk-abcdefghijklmnop/);
  assert.match(json,/redacted/);
  assert.equal(result.sessions[0].activities[0].title,'Run command');
  assert.equal(result.sessions[0].pendingCount,1);
});

test('metadata pagination and reads are bounded, capped three concurrent, and owned sessions never read', async () => {
  const threads = Array.from({length:150},(_,i)=>session(`thread_${String(i).padStart(3,'0')}`,'Name','Preview',i));
  let running = 0, maxRunning = 0, reads = 0;
  const api = async path => {
    if (path.startsWith('/api/threads?')) return {threads,nextCursor:null};
    const id = path.split('/')[3].split('?')[0];
    assert.notEqual(id,'thread_149');
    reads++; maxRunning = Math.max(maxRunning,++running);
    await new Promise(resolve=>setTimeout(resolve,3)); running--;
    return history(id,'Request','Response');
  };
  const result = await new CoordinatorDiscovery({api,owns:id=>id==='thread_149'}).discover({threadIds:['thread_149'],limit:30});
  assert.equal(maxRunning,3);
  assert.equal(reads,12);
  assert.equal(result.catalog.length,120);
  assert.equal(result.listedCount,149);
  assert.equal(result.truncated,true);
  assert.equal(result.scope.complete,false);
});

test('cursor continuation preserves incomplete scope, pagination guard and deeper candidate references', async () => {
  const calls = [];
  const api = async path => {
    calls.push(path);
    if (path.startsWith('/api/threads?')) return {threads:[session('old_page')], nextCursor:'old_cursor'};
    return history('old_page','Older meaningful request','Older result');
  };
  const result = await new CoordinatorDiscovery({api}).discover({cursor:'prior page',threadIds:['old_page']});
  assert.match(calls[0],/view=coordinator&cursor=prior%20page/);
  assert.equal(result.scope.nextCursor,'old_cursor');
  assert.equal(result.scope.complete,false);
  assert.equal(result.errors[0].code,'repeated_page');
});

test('per-read timeout and mismatched routing cannot publish stale evidence or mutate a finished snapshot', async () => {
  let late;
  const api = async path => {
    if (path.startsWith('/api/threads?')) return {threads:[session('late_thread'),session('wrong_thread')]};
    if (path.includes('wrong_thread')) return history('other_thread','Wrong request','Wrong answer');
    return new Promise(resolve=>{late=resolve;});
  };
  const result = await new CoordinatorDiscovery({api,readTimeoutMs:15,totalTimeoutMs:60}).discover({limit:2});
  assert.equal(result.sessions.find(s=>s.id==='late_thread').readError,'timeout');
  assert.equal(result.sessions.find(s=>s.id==='wrong_thread').readError,'mismatched_thread');
  const before = JSON.stringify(result);
  late(history('late_thread','Late private context','Late response'));
  await new Promise(resolve=>setTimeout(resolve,5));
  assert.equal(JSON.stringify(result),before);
});

test('bounded evidence retains latest user even when assistant commentary dominates', async () => {
  const rows = [{type:'userMessage',text:'Find Google referral roles'},
    ...Array.from({length:40},(_,i)=>({type:'agentMessage',text:`Update ${i}`}))];
  const data = fake([session('busy_thread')],{busy_thread:{thread:{id:'busy_thread'},timeline:{rows}}});
  const result = await new CoordinatorDiscovery(data).discover();
  assert.equal(result.sessions[0].latestUser,'Find Google referral roles');
  assert.equal(result.sessions[0].messages.length,8);
});

test('six-page catalog bound retains explicit old metadata and gives a continuation cursor', async () => {
  let pages = 0;
  const api = async path => {
    if (path.startsWith('/api/threads?')) {
      const page = pages++;
      return {threads:Array.from({length:100},(_,i)=>session(`page_${page}_${i}`,'Long '.repeat(50),'Preview '.repeat(90),page*100+i)), nextCursor:`cursor_${page+1}`};
    }
    const id = path.split('/')[3].split('?')[0];
    return history(id,'Requested ongoing work','Still running');
  };
  const result = await new CoordinatorDiscovery({api}).discover({threadIds:['page_0_0'],limit:1});
  assert.equal(pages,6);
  assert.equal(result.listedCount,600);
  assert.equal(result.catalogCount,120);
  assert.equal(result.catalog[0].id,'page_0_0');
  assert.equal(result.catalog[0].name.length,120);
  assert.equal(result.catalog[0].preview.length,220);
  assert.equal(result.scope.nextCursor,'cursor_6');
  assert.equal(result.scope.complete,false);
});

test('total deadline prevents further reads and missing timeline does not claim history was read', async () => {
  let reads = 0;
  const api = async path => {
    if (path.startsWith('/api/threads?')) return {threads:Array.from({length:6},(_,i)=>session(`thread_${i}`))};
    reads++;
    if (path.includes('thread_0')) return {thread:{id:'thread_0'}};
    return new Promise(()=>{});
  };
  const result = await new CoordinatorDiscovery({api,totalTimeoutMs:25,readTimeoutMs:100}).discover({limit:6});
  assert.equal(result.sessions.find(s=>s.id==='thread_0').history.scope,'unavailable');
  assert.ok(reads <= 4);
  assert.equal(result.sessions.filter(s=>s.readError==='timeout').length,5);
});

test('initial context is small metadata only and never waits for unrelated histories', async () => {
 const calls=[];const threads=Array.from({length:160},(_,i)=>session(`thread_${i}`,'Work', 'Current preview',i));
 const discovery=new CoordinatorDiscovery({api:async path=>{calls.push(path);if(path.includes('?'))return {threads,nextCursor:'next-page'};throw Error('Unrelated history must not run');}});
 const result=await discovery.initialSnapshot({threadIds:['thread_90']});
 assert.equal(calls.length,1);assert.equal(result.catalog.length,16);assert.equal(result.catalog[0].id,'thread_90');
 assert.deepEqual(result.sessions,[]);assert.equal(result.deferred,true);assert.equal(result.scope.complete,false);
 assert.ok(JSON.stringify(result).length<16000);
});
