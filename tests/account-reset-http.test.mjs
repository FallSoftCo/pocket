import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {humanInputRoute} from '../server/human-ingress.mjs';
import {registerAccountResetRoutes} from '../server/account-reset-routes.mjs';

test('authenticated account reset HTTP binds account and paired human before invoking shared runner',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'manual-reset-http-'));
 await writeFile(join(dir,'account-reset-policy.json'),JSON.stringify({schemaVersion:2,mode:'standing',accountId:'private-account',enabled:false,status:'paused'}));
 const app=express();app.use(express.json());
 app.use((req,res,next)=>{req.device={id:req.get('Authorization')==='Bearer paired'?'phone':'owner'};if(humanInputRoute(req.method,req.path)&&req.device.id==='owner')return res.sendStatus(403);req.humanPrincipal={deviceId:'phone'};next();});
 const rateLimits={codex:{socket:'/synthetic.sock'},accountId:'private-account',refresh:async()=>{},snapshot:()=>({resetCredits:{availableCount:1}})};
 const calls=[];registerAccountResetRoutes(app,{dir,root:process.cwd(),secrets:{adminToken:'synthetic-private-key'},rateLimits,route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next),admissions:{record:(...args)=>calls.push(['admission',...args])},invoke:async args=>{calls.push(['invoke',args.request]);return {state:'acknowledged',message:'Reset applied.'};}});
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 t.after(async()=>{await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 const url=`http://127.0.0.1:${server.address().port}/api/account/reset`;
 const info=await (await fetch(url)).json();assert.equal(info.available,true);assert.equal(info.automatic,false);assert.ok(!JSON.stringify(info).includes('private-account'));
 const body={id:'manual-http-request-01',accountToken:info.accountToken};
 const post=auth=>fetch(url,{method:'POST',headers:{Authorization:auth,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post('Bearer owner')).status,403);assert.equal(calls.length,0);
 rateLimits.accountId='different-account';assert.equal((await post('Bearer paired')).status,409);assert.equal(calls.length,0);
 rateLimits.accountId='private-account';const result=await post('Bearer paired');assert.equal(result.status,200);assert.equal((await result.json()).state,'acknowledged');
 assert.equal(calls[1][1].accountId,'private-account');assert.equal(calls[1][1].deviceId,'phone');
});
