import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createTailnetBrowserAuth} from '../server/tailnet-browser-auth.mjs';
import {createWebClient} from '../server/web-client.mjs';

test('tailnet browser sign-in verifies source, creates a normal device, and keeps credentials in a protected cookie',async t=>{
 const owner='private-owner',token='d'.repeat(64),calls=[];
 const backend=http.createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);
  calls.push({path:req.url,auth:req.headers.authorization,body:chunks.length?JSON.parse(Buffer.concat(chunks)):null});
  res.setHeader('Content-Type','application/json');
  if(req.url==='/api/pairing'){assert.equal(req.headers.authorization,`Bearer ${owner}`);res.end(JSON.stringify({code:'temporary'}));}
  else if(req.url==='/api/pair'){assert.equal(calls.at(-1).body.code,'temporary');assert.equal(req.headers.authorization,undefined);res.end(JSON.stringify({token,id:'new-browser',host:'fixture'}));}
  else if(req.headers.authorization===`Bearer ${token}`)res.end(JSON.stringify({ok:true}));
  else{res.statusCode=401;res.end('{}');}
 });backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>backend.close());
 const origin='https://private.example.test',url=`http://127.0.0.1:${backend.address().port}`;
 const whois=async ip=>({Node:{ID:7,Name:'bld.private.',Addresses:['100.104.158.74/32']},UserProfile:{LoginName:'owner@example.test'}});
 const gateway=createWebClient({origin,backend:url,tailnetAuth:createTailnetBrowserAuth({backend:url,ownerToken:owner,whois})});
 gateway.server.listen(0,'127.0.0.1');await once(gateway.server,'listening');t.after(()=>gateway.server.close());
 const base=`http://127.0.0.1:${gateway.server.address().port}`,headers={Origin:origin,'Content-Type':'application/json','X-Forwarded-For':'100.104.158.74','Tailscale-User-Login':'owner@example.test'};
 assert.equal((await fetch(base+'/web/tailnet-login',{method:'POST',headers:{...headers,Origin:'https://foreign.test'},body:'{}'})).status,403);
 assert.equal((await fetch(base+'/web/tailnet-login',{method:'POST',headers:{...headers,'X-Forwarded-For':'8.8.8.8'},body:'{}'})).status,403);
 assert.equal((await fetch(base+'/web/tailnet-login',{method:'POST',headers:{...headers,'Tailscale-User-Login':'impostor'},body:'{}'})).status,403);
 assert.equal(calls.length,0);
 const login=await fetch(base+'/web/tailnet-login',{method:'POST',headers,body:'{}'});
 assert.equal(login.status,200);assert.deepEqual(await login.json(),{id:'new-browser',host:'fixture'});
 const cookie=login.headers.get('set-cookie');assert.match(cookie,/HttpOnly/);assert.match(cookie,/SameSite=Strict/);assert.match(cookie,/Secure/);
 assert.equal((await fetch(base+'/api/status',{headers:{Cookie:cookie.split(';')[0]}})).status,200);
 assert.equal((await fetch(base+'/api/devices',{headers:{Cookie:cookie.split(';')[0]}})).status,403);
 assert.deepEqual(calls.map(c=>c.path),['/api/pairing','/api/pair','/api/status']);
});

test('unverified addresses and direct connections cannot provision; tagged tailnet devices can',async()=>{
 let writes=0;const identity={Node:{ID:2,Addresses:['100.70.1.2/32']}};
 const auth=createTailnetBrowserAuth({backend:'http://127.0.0.1:1',ownerToken:'private',whois:async()=>identity,request:async(url)=>{writes++;return {ok:true,json:async()=>url.pathname==='/api/pairing'?{code:'once'}:{token:'a'.repeat(64),id:'tagged'}};}});
 await assert.rejects(auth({socket:{remoteAddress:'100.70.1.2'},headers:{'x-forwarded-for':'100.70.1.2'}}));
 await assert.rejects(auth({socket:{remoteAddress:'127.0.0.1'},headers:{'x-forwarded-for':'100.70.1.3'}}));
 assert.equal(writes,0);
 assert.equal((await auth({socket:{remoteAddress:'127.0.0.1'},headers:{'x-forwarded-for':'8.8.8.8, 100.70.1.2'}})).id,'tagged');
 assert.equal(writes,2);
});
