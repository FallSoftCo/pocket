import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createWebClient} from '../server/web-client.mjs';
import {WebEnvironments} from '../server/web-environments.mjs';
test('room roster authenticates the original browser without stock inventory or credentials',async t=>{
 const token='a'.repeat(64),calls=[];let revoked=false;
 const upstream=http.createServer((req,res)=>{calls.push(req.url);res.setHeader('Content-Type','application/json');
  if(req.url==='/api/pair')return res.end(JSON.stringify({token,id:'browser'}));
  if(revoked||req.headers.authorization!=='Bearer '+token){res.statusCode=401;return res.end('{}');}
  if(req.url==='/api/status')return res.end(JSON.stringify({deviceId:'browser',host:'Native room host'}));
  res.statusCode=403;res.end('{}');
 });upstream.listen(0,'127.0.0.1');await once(upstream,'listening');t.after(()=>upstream.close());
 const env=new WebEnvironments({secure:false,profiles:{browser:[{id:'gpu',name:'GPU',base:'http://127.0.0.1:19427',token:'secret-profile-value',deviceId:'remote'}]}});
 const origin='https://private.example.test',g=createWebClient({backend:'http://127.0.0.1:'+upstream.address().port,origin,environments:env});g.server.listen(0,'127.0.0.1');await once(g.server,'listening');t.after(()=>g.server.close());const base='http://127.0.0.1:'+g.server.address().port;
 assert.equal((await fetch(base+'/web/environments')).status,401);assert.deepEqual(calls,[]);
 const pair=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:'{}'}),cookie=pair.headers.get('set-cookie').split(';')[0];
 const response=await fetch(base+'/web/environments',{headers:{Cookie:cookie}});assert.equal(response.status,200);assert.deepEqual(await response.json(),{retiredEnvironmentIds:[],environments:[{id:'current',name:'Native room host',available:true},{id:'gpu',name:'GPU',available:true}]});assert.deepEqual(calls,['/api/pair','/api/status']);
 revoked=true;const denied=await fetch(base+'/web/environments',{headers:{Cookie:cookie}});assert.equal(denied.status,401);assert.match(denied.headers.get('set-cookie'),/Max-Age=0/);
});
