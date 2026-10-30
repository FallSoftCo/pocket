import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,statSync,readdirSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {WebFleet} from '../server/web-fleet.mjs';
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const fleet={version:1,id:id(1),accountId:id(2),nodeId:id(3),sessionPath:'/api/fleet/session',enrollmentPath:'/api/fleet/enroll',members:[
 {id:id(3),name:'CPU',origin:'https://cpu.test:19426'},
 {id:id(4),name:'GPU',origin:'https://gpu.test:19426'},
 {id:id(5),name:'Workstation',origin:'https://workstation.test:8447'}]};
const token='source-pairing-'.padEnd(64,'s'),portable='portable-'.padEnd(64,'p');
const enrollment=node=>({version:1,fleetId:id(1),accountId:id(2),nodeId:node,ownerDeviceId:id(6),deviceId:id(8),token:'local-'+node.padEnd(64,'x')});

test('web enrollment reconciles a lost ACK after restart without repeating admission; unrelated unavailable member does not block it',async()=>{
 const directory=mkdtempSync(join(tmpdir(),'nextcomp-web-fleet-'));let accepted=0;const receipts=new Map();let sourceOnline=true;
 const calls=[];
 const fetchImpl=async(url,options)=>{
  const u=new URL(url);calls.push({origin:u.origin,path:u.pathname,auth:options.headers.Authorization});
  if(u.hostname==='127.0.0.1'){
   if(!sourceOnline)throw Error('offline');assert.equal(options.headers.Authorization,'Bearer '+token);
   if(u.pathname==='/api/backends')return reply({fleet});
   assert.equal(u.pathname,'/api/fleet/session');return reply({version:1,fleetId:id(1),accountId:id(2),deviceId:id(6),token:portable,expiresAt:Date.now()+60000});
  }
  assert.equal(options.headers.Authorization,'Bearer '+portable);assert.equal(u.hostname,'gpu.test');
  if(u.pathname==='/api/fleet/enroll'){
   const op=JSON.parse(options.body).operationId;accepted++;receipts.set(op,enrollment(id(4)));throw Error('ACK lost after acceptance');
  }
  return reply(receipts.get(u.pathname.split('/').at(-1))||{},receipts.has(u.pathname.split('/').at(-1))?200:404);
 };
 // Workstation fails independently, without seeing the source's opaque credential.
 const isolatedFetch=(url,options)=>new URL(url).hostname==='workstation.test'?Promise.reject(Error('unreachable')):fetchImpl(url,options);
 try{
  const first=new WebFleet({directory,backend:'http://127.0.0.1:19427',fetchImpl:isolatedFetch});first.observe(id(7),token);await first.flights.get(id(7));
  assert.equal(accepted,1);assert.equal(first.list(id(7)).length,0);
  const second=new WebFleet({directory,backend:'http://127.0.0.1:19427',fetchImpl:isolatedFetch});second.observe(id(7),token);await second.flights.get(id(7));
  assert.equal(accepted,1);assert.equal(second.list(id(7)).length,1);assert.equal(second.list(id(7))[0].nodeId,id(4));
  sourceOnline=false;
  const offline=new WebFleet({directory,backend:'http://127.0.0.1:19427',fetchImpl:isolatedFetch});
  assert.equal(offline.list(id(7))[0].token,enrollment(id(4)).token);
  assert.ok(calls.filter(c=>c.origin!=='http://127.0.0.1:19427').every(c=>c.auth!=='Bearer '+token));
  const files=readdirSync(directory);assert.equal(files.length,1);assert.equal(statSync(join(directory,files[0])).mode&0o077,0);
 }finally{rmSync(directory,{recursive:true,force:true});}
});
