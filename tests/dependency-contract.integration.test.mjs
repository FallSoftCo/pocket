// Dependency migration contracts use the real MCP executables and SDK transport.
// All HTTP actions terminate at this disposable loopback receiver, never a phone,
// notification service, terminal or provider. Application validation and handlers
// are unmodified; the receiver supplies fixed transport responses only.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp, mkdir, writeFile, readFile, rm, access} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {gzipSync} from 'node:zlib';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';
import {productionFixture} from './helpers/production-native-fixture.mjs';

async function receiver(t) {
  const dir = await mkdtemp(join(tmpdir(), 'nextcomp-dependency-'));
  const requests = [];
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : undefined;
    requests.push({path:req.url, method:req.method, auth:req.headers.authorization, body});
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/api/notify') res.end(JSON.stringify({notification:{id:'fixture-notification'}}));
    else if (req.url === '/api/terminal-forks/checkpoint-1') res.end(JSON.stringify({id:'checkpoint-1', sourceThreadId:'fixture-thread', state:'prepared'}));
    else if (req.url === '/action') res.end(JSON.stringify({ok:true}));
    else { res.statusCode = 404; res.end(JSON.stringify({error:'Unexpected fixture route'})); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(dir, {recursive:true, force:true}); });
  return {dir, requests, base:`http://127.0.0.1:${server.address().port}`};
}

async function connect(t, file, fixture, extra = {}) {
  const client = new Client({name:'dependency-contract', version:'1'}, {capabilities:{}});
  const transport = new StdioClientTransport({command:process.execPath, args:[file], cwd:new URL('../', import.meta.url).pathname,
    env:{PATH:process.env.PATH, HOME:fixture.dir, CODEX_HOME:fixture.dir, POCKET_DATA:fixture.dir,
      POCKET_TOKEN:'fixture-secret', POCKET_URL:fixture.base, CODEX_THREAD_ID:'fixture-thread', ...extra}, stderr:'pipe'});
  let errors = '';
  t.after(() => client.close());
  await client.connect(transport);
  transport.stderr?.on('data', data => { errors += data; });
  t.after(() => assert.doesNotMatch(errors, /uncaught|unhandled/i));
  return client;
}

async function rejected(client, name, args, fixture) {
  const count = fixture.requests.length;
  const result = await client.callTool({name, arguments:args});
  assert.equal(result.isError, true, `${name} must reject invalid input: ${JSON.stringify(result)}`);
  assert.match(result.content[0].text, /validation|invalid|too.big|too.small/i);
  assert.equal(fixture.requests.length, count, `${name} must reject before HTTP side effects`);
  return result;
}

test('Zod migration preserves real notification and terminal MCP schema/default/rejection contracts', {timeout:20000}, async t => {
  const f = await receiver(t);
  await writeFile(join(f.dir, 'terminal-forks.json'), JSON.stringify({targets:{'fixture-target':{sshHost:'never-connect'}}}));
  const client = await connect(t, 'server/mcp.mjs', f);
  const tools = (await client.listTools()).tools;
  assert.deepEqual(tools.map(tool => tool.name).sort(), ['notify_user', 'terminal_fork']);
  const notify = tools.find(tool => tool.name === 'notify_user').inputSchema;
  assert.deepEqual([...notify.required].sort(), ['message','thread_id','title']);
  assert.equal(notify.properties.title.maxLength, 180);
  assert.equal(notify.properties.files.maxItems, 5);
  assert.equal(notify.properties.kind.default, 'update');
  const args = {thread_id:'fixture-thread', title:'Synthetic result', message:'Synthetic only', files:['/synthetic/report.txt']};
  const result = await client.callTool({name:'notify_user', arguments:args});
  assert.equal(result.isError, undefined);
  assert.deepEqual(JSON.parse(result.content[0].text), {notification_id:'fixture-notification', status:'accepted_by_pocket', thread_id:args.thread_id});
  assert.deepEqual(f.requests[0], {path:'/api/notify', method:'POST', auth:'Bearer fixture-secret', body:{...args, kind:'update'}});
  await rejected(client, 'notify_user', {...args, title:'x'.repeat(181)}, f);
  await rejected(client, 'notify_user', {...args, kind:'unsupported'}, f);
  const oversized = await rejected(client, 'notify_user', {...args, files:Array(64).fill(42)}, f);
  // GHSA-mrhx-c7xc-m3w5: bounded oversize arrays must be rejected before
  // accumulating an error for every invalid element. Exercise the actual tool.
  assert.ok(oversized.content[0].text.length < 1200, 'oversize collection must not accumulate per-element validation errors');
  const fork = tools.find(tool => tool.name === 'terminal_fork').inputSchema;
  assert.equal(fork.properties.workspace_files.maxItems, 256);
  assert.equal(fork.properties.dependencies.maxItems, 64);
  const status = {operation:'status', migration_id:'checkpoint-1', source_thread_id:'fixture-thread'};
  const receipt = await client.callTool({name:'terminal_fork', arguments:status});
  assert.equal(JSON.parse(receipt.content[0].text).state, 'prepared');
  assert.equal(f.requests.at(-1).method, 'GET');
  await rejected(client, 'terminal_fork', {...status, dependencies:Array(65).fill({id:'fixture',state:'settled',description:'fixture'})}, f);
});

test('Zod migration preserves actual phone MCP integer, path, enum and text validation before HTTP', {timeout:20000}, async t => {
  const f = await receiver(t), secretFile = join(f.dir, 'automation-secret.json');
  await writeFile(secretFile, JSON.stringify({secret:'fixture-phone-secret'}));
  const client = await connect(t, 'server/phone-mcp.mjs', f, {POCKET_AUTOMATION_URL:f.base, POCKET_AUTOMATION_SECRET_FILE:secretFile});
  const tools = (await client.listTools()).tools;
  assert.deepEqual(tools.map(tool => tool.name).sort(), ['phone_click','phone_key','phone_screen','phone_screenshot','phone_scroll','phone_tap','phone_type']);
  assert.equal(tools.find(tool => tool.name === 'phone_tap').inputSchema.properties.x.type, 'integer');
  for (const [name, args, body] of [
    ['phone_tap',{x:0,y:1},{type:'tap',x:0,y:1}],
    ['phone_click',{path:'0.2'},{type:'click',path:'0.2'}],
    ['phone_type',{text:'Synthetic only'},{type:'text',text:'Synthetic only'}],
  ]) {
    const result = await client.callTool({name, arguments:args});
    assert.equal(result.isError, undefined);
    assert.deepEqual(f.requests.at(-1), {path:'/action',method:'POST',auth:'Bearer fixture-phone-secret',body});
  }
  for (const [name,args] of [['phone_tap',{x:-1,y:0}], ['phone_tap',{x:0.5,y:0}], ['phone_click',{path:'0..2'}], ['phone_scroll',{direction:'diagonal'}], ['phone_key',{key:'power'}], ['phone_type',{text:'x'.repeat(8001)}]]) await rejected(client,name,args,f);
});

test('patched body parser survives compressed trailing data in the actual backend', {timeout:20000}, async t => {
  const f = await productionFixture(t);
  const body = Buffer.concat([gzipSync('{"synthetic":true}'), Buffer.from('invalid trailing compressed data')]);
  const response = await fetch(f.base+'/api/pair', {method:'POST',headers:{'Content-Type':'application/json','Content-Encoding':'gzip'},body,signal:AbortSignal.timeout(5000)});
  assert.equal(response.status, 400);
  await response.text();
  const health = await fetch(f.base+'/health', {signal:AbortSignal.timeout(5000)});
  assert.equal(health.status, 200, 'malformed compressed request must leave backend available');
  const current = await f.api('/api/threads/thread-shared/native-timeline');
  assert.ok(current, 'authenticated native-backed reads still work');
  f.assertClean();
});

test('Google optional clients resolve the compatible patched cleanup API', {timeout:10000}, async t => {
  const dir = await mkdtemp(join(tmpdir(), 'nextcomp-cleanup-contract-'));
  t.after(() => rm(dir, {recursive:true,force:true}));
  const require = createRequire(import.meta.url);
  const lock = JSON.parse(await readFile(new URL('../package-lock.json',import.meta.url)));
  const consumers = Object.entries(lock.packages).filter(([,value]) => value.dependencies?.rimraf);
  assert.ok(consumers.length > 0, 'exercise actual installed cleanup consumers');
  for (const [path] of consumers) {
    const consumerRequire = createRequire(new URL(`../${path}/package.json`,import.meta.url));
    const {rimraf, rimrafSync} = consumerRequire('rimraf');
    const target = join(dir, String(consumers.findIndex(([p]) => p === path)));
    await mkdir(target); await writeFile(join(target, 'fixture'), 'disposable');
    assert.equal(await rimraf(target), true); await assert.rejects(access(target), {code:'ENOENT'});
    await mkdir(target); assert.equal(rimrafSync(target), true); await assert.rejects(access(target), {code:'ENOENT'});
  }
  // Loading clients does not create credentials, connections or model calls.
  assert.equal(typeof require('@google-cloud/firestore').Firestore, 'function');
  assert.equal(typeof require('google-auth-library').GoogleAuth, 'function');
});
