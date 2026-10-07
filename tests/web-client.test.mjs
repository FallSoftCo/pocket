import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createWebClient} from '../server/web-client.mjs';
import {sessionIdentity,workTime,compareWork,mergeRows} from '../web/model.mjs';
import WebSocket,{WebSocketServer} from 'ws';

test('web gateway authenticates pairing, protects credential and blocks foreign origins and owner routes',async t=>{
 const token='a'.repeat(64);let observed;
 const upstream=http.createServer(async(req,res)=>{observed={url:req.url,authorization:req.headers.authorization};res.setHeader('Content-Type','application/json');if(req.url==='/api/pair')res.end(JSON.stringify({token,id:'browser',host:'fixture'}));else if(req.headers.authorization!==`Bearer ${token}`){res.statusCode=401;res.end(JSON.stringify({error:'Unauthorized'}));}else res.end(JSON.stringify({ok:true}));});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');t.after(()=>upstream.close());
 const backend=`http://127.0.0.1:${upstream.address().port}`,origin='https://private.example.test';const gateway=createWebClient({backend,origin});gateway.server.listen(0,'127.0.0.1');await once(gateway.server,'listening');t.after(()=>gateway.server.close());const base=`http://127.0.0.1:${gateway.server.address().port}`;
 assert.equal((await fetch(base+'/api/status')).status,401);
 assert.equal((await fetch(base+'/web/pair',{method:'POST',headers:{Origin:'https://evil.example'},body:'{}'})).status,403);
 const pair=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:'{"code":"fixture"}'});assert.equal(pair.status,200);assert.equal((await pair.json()).token,undefined);const header=pair.headers.get('set-cookie');assert.match(header,/HttpOnly/);assert.match(header,/Secure/);assert.match(header,/SameSite=Strict/);const cookie=header.split(';')[0];
 const r=await fetch(base+'/api/status',{headers:{Cookie:cookie}});assert.equal(r.status,200);assert.equal(observed.authorization,`Bearer ${token}`);
 assert.equal((await fetch(base+'/api/status',{headers:{Cookie:cookie+'tampered'}})).status,401);
 assert.equal((await fetch(base+'/api/devices',{headers:{Cookie:cookie}})).status,403);
 assert.equal((await fetch(base+'/api/notify',{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:'{}'})).status,403);
 assert.equal((await fetch(base+'/api/threads/thread-123/reply',{method:'POST',headers:{Cookie:cookie,Origin:'https://evil.example','Content-Type':'application/json'},body:'{}'})).status,403);
 const staticResponse=await fetch(base+'/');assert.match(staticResponse.headers.get('content-security-policy'),/frame-ancestors 'none'/);assert.match(await staticResponse.text(),/NextComp/);
 const logout=await fetch(base+'/web/logout',{method:'POST',headers:{Cookie:cookie,Origin:origin}});assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
});

test('work recency ignores metadata touching and child replies require explicit capability',()=>{
 assert.equal(sessionIdentity(null).canAcceptDirectInput,true);
 const child={id:'child-123',source:{subAgent:{thread_spawn:{parent_thread_id:'parent-123',agent_nickname:'Review'}}},updatedAt:9999999999999,createdAt:100};
 assert.equal(sessionIdentity(child).parentThreadId,'parent-123');assert.equal(sessionIdentity(child).canAcceptDirectInput,false);assert.equal(sessionIdentity({...child,canAcceptDirectInput:true}).canAcceptDirectInput,true);assert.equal(sessionIdentity({forkedFromId:'parent-123'}).isChild,false);assert.equal(workTime(child),100000);
 assert.deepEqual([child,{id:'parent-123',createdAt:200}].sort(compareWork).map(t=>t.id),['parent-123','child-123']);
 assert.deepEqual(mergeRows([{id:'a',text:'new',version:2}],[{id:'a',text:'old',version:1},{id:'b',text:'next'}]),[{id:'a',text:'new',version:2},{id:'b',text:'next'}]);
});

test('gateway checks child capability before forwarding guidance and enriches real identity without renaming',async t=>{
 const token='b'.repeat(64);let writes=0;const upstream=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');if(req.url==='/api/pair')res.end(JSON.stringify({token,id:'test'}));else if(req.method==='POST'){writes++;res.end('{}');}else res.end(JSON.stringify({threads:[{id:'parent-123',name:'Canonical name',preview:'Useful answer',updatedAt:900}]}));});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');t.after(()=>upstream.close());
 const metadata={async read(id){return {id,parentThreadId:'parent-123',isChild:true,canAcceptDirectInput:false};},async list(){return {threads:[{id:'parent-123',createdAt:100},{id:'child-123',parentThreadId:'parent-123',isChild:true,canAcceptDirectInput:false,createdAt:200}],partial:false};}};
 const origin='https://private.example.test';const gateway=createWebClient({origin,backend:`http://127.0.0.1:${upstream.address().port}`,metadata});gateway.server.listen(0,'127.0.0.1');await once(gateway.server,'listening');t.after(()=>gateway.server.close());const base=`http://127.0.0.1:${gateway.server.address().port}`;const pair=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:'{}'}),cookie=pair.headers.get('set-cookie').split(';')[0];
 const r=await fetch(base+'/api/threads/child-123/reply',{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:'{"text":"test"}'});assert.equal(r.status,409);assert.equal(writes,0);
 const list=await (await fetch(base+'/api/threads',{headers:{Cookie:cookie}})).json();assert.equal(list.threads.length,2);assert.equal(list.threads[0].name,'Canonical name');assert.equal(list.threads[1].parentThreadId,'parent-123');
});

test('browser WebSocket upgrades require paired cookie and exact origin; upstream uses only device bearer',async t=>{
 const token='c'.repeat(64);let seenToken;const upstream=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify({token,id:'test'}));});const wss=new WebSocketServer({server:upstream});wss.on('connection',(ws,req)=>{seenToken=req.headers.authorization;ws.on('message',m=>ws.send(m));});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');t.after(()=>{wss.close();upstream.close();});const origin='https://private.example.test',gateway=createWebClient({origin,backend:`http://127.0.0.1:${upstream.address().port}`});gateway.server.listen(0,'127.0.0.1');await once(gateway.server,'listening');t.after(()=>gateway.server.close());const base=`http://127.0.0.1:${gateway.server.address().port}`;const pair=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:'{}'}),cookie=pair.headers.get('set-cookie').split(';')[0];
 const denied=new WebSocket(base.replace('http:','ws:')+'/events',{headers:{Cookie:cookie,Origin:'https://evil.example'}});denied.on('error',()=>{});assert.equal(await new Promise(resolve=>denied.on('unexpected-response',(_req,r)=>{r.resume();denied.terminate();resolve(r.statusCode);})),401);
 const ws=new WebSocket(base.replace('http:','ws:')+'/events',{headers:{Cookie:cookie,Origin:origin}});await once(ws,'open');ws.send('browser fixture');assert.equal((await once(ws,'message'))[0].toString(),'browser fixture');assert.equal(seenToken,`Bearer ${token}`);ws.close();await once(ws,'close');
});

test('unsupported child history is read without acquiring writer ownership and still requires device auth',async t=>{
 const token='e'.repeat(64);const calls=[];const upstream=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');if(req.url==='/api/pair')res.end(JSON.stringify({token,id:'test'}));else if(req.url==='/api/status'&&req.headers.authorization===`Bearer ${token}`)res.end('{}');else{res.statusCode=401;res.end('{"error":"Unauthorized"}');}});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');t.after(()=>upstream.close());
 const metadata={async read(id){calls.push('metadata');return {id,isChild:true,parentThreadId:'parent-123',canAcceptDirectInput:false};},async history(thread){calls.push('read-history');return {thread,timeline:{rows:[{id:'answer',kind:'message',text:'Delegated result'}]},pending:[],outgoing:[],historyReadOnly:true};}};
 const origin='https://private.example.test',gateway=createWebClient({origin,metadata,backend:`http://127.0.0.1:${upstream.address().port}`});gateway.server.listen(0,'127.0.0.1');await once(gateway.server,'listening');t.after(()=>gateway.server.close());const base=`http://127.0.0.1:${gateway.server.address().port}`;
 assert.equal((await fetch(base+'/api/threads/child-123?view=timeline')).status,401);assert.deepEqual(calls,[]);
 const pair=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:'{}'}),cookie=pair.headers.get('set-cookie').split(';')[0];const data=await (await fetch(base+'/api/threads/child-123?view=timeline',{headers:{Cookie:cookie}})).json();assert.equal(data.historyReadOnly,true);assert.equal(data.timeline.rows[0].text,'Delegated result');assert.deepEqual(calls,['metadata','read-history']);
});
