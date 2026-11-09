import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {spawn} from 'node:child_process';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {standingAccountReset} from '../server/account-reset.mjs';

async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'banked-reset-')),path=join(dir,'policy.json'),legacyPath=join(dir,'oneoff.json'),socket=join(dir,'native.sock');
 const legacy={enabled:true,status:'redeemed',accountId:'fixture-account',creditId:'old-benefit',idempotencyKey:'original-key',outcome:'reset'};
 const original=JSON.stringify(legacy)+'\n';await writeFile(legacyPath,original);
 await writeFile(path,JSON.stringify(standingAccountReset(legacy,{accountId:legacy.accountId,authorizationReference:'synthetic-standing-request'})));
 const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
 const f={used:98,accountId:legacy.accountId,spend:false,individual:null,credits:[],calls:[],consume:[],seen:new Set(),loseAck:false,outcome:'reset',readFailure:false,extraWindow:null,foreignBucket:false,foreignDefault:false};
 const now=Math.floor(Date.now()/1000);
 f.credit=(id,expiresAt=now+3600)=>({id,expiresAt,status:'available',resetType:'codexRateLimits'});
 wss.on('connection',ws=>ws.on('message',async raw=>{
  const m=JSON.parse(raw);if(!m.id)return;f.calls.push(m.method);
  const reply=result=>ws.send(JSON.stringify({id:m.id,result}));
  if(m.method==='initialize'){reply({});return;}
  if(m.method==='account/read'){reply({account:{type:'chatgpt'}});return;}
  if(m.method==='account/rateLimits/read'){
   if(f.readFailure){ws.close();return;}
   const bucket={limitId:f.foreignDefault?'unrelated':null,primary:{usedPercent:f.used,windowDurationMins:10080,resetsAt:now+60},secondary:f.extraWindow,spendControlReached:f.spend,individualLimit:f.individual};
   reply({accountId:f.accountId,rateLimits:bucket,...(f.foreignBucket?{rateLimitsByLimitId:{unrelated:bucket}}:{}),rateLimitResetCredits:{availableCount:f.credits.length,credits:f.credits}});return;
  }
  assert.equal(m.method,'account/rateLimitResetCredit/consume','Only account metadata/earned-reset RPCs are permitted');
  const persisted=JSON.parse(await readFile(path,'utf8'));
  assert.equal(persisted.status,'redeeming');assert.equal(persisted.attempt.status,'pending');
  assert.deepEqual(m.params,{creditId:persisted.attempt.creditId,idempotencyKey:persisted.attempt.idempotencyKey},'exact intent must be on disk before native dispatch');
  f.consume.push(m.params);const duplicate=f.seen.has(m.params.idempotencyKey);f.seen.add(m.params.idempotencyKey);
  if(f.loseAck){f.loseAck=false;ws.close();return;}
  reply({outcome:duplicate?'alreadyRedeemed':f.outcome});
 }));
 f.state=async()=>JSON.parse(await readFile(path,'utf8'));
 f.tick=async patch=>{
  const state=await f.state();await writeFile(path,JSON.stringify({...state,...patch,nextAt:0}));
  const child=spawn(process.execPath,['scripts/account-reset.mjs'],{cwd:process.cwd(),env:{PATH:process.env.PATH,HOME:dir,CODEX_HOME:dir,CODEX_SOCKET:socket,POCKET_RESET_STATE:path},stdio:['ignore','pipe','pipe']});
  let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
  const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});
  assert.equal(code,0,output);assert.ok(!output.includes('fixture-account')&&!output.includes('original-key'));
  assert.equal(await readFile(legacyPath,'utf8'),original,'original oneoff receipt stays byte-identical');
  return f.state();
 };
 t.after(async()=>{for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 return f;
}

test('standing native runner persists exact attempts across restarts and rearms only after observed usage recovery',async t=>{
 const f=await fixture(t);
 f.credits=[f.credit('later',null),f.credit('soon'),f.credit('expired',1)];
 assert.equal((await f.tick()).status,'armed');assert.equal(f.consume.length,0);
 f.used=99;f.loseAck=true;let s=await f.tick();assert.equal(s.status,'redeeming');const exact={...f.consume[0]};assert.equal(exact.creditId,'soon');
 f.accountId='other';assert.equal((await f.tick()).status,'redeeming');assert.equal(f.consume.length,1);f.accountId='fixture-account';
 f.spend=true;assert.equal((await f.tick()).status,'redeeming');assert.equal(f.consume.length,1);f.spend=false;
 // Unknown delivery reconciles even after inventory vanishes and usage drops.
 f.credits=[];f.used=10;s=await f.tick();assert.equal(s.status,'awaitingUsageDrop');assert.equal(s.attempt.outcome,'alreadyRedeemed');assert.deepEqual(f.consume,[exact,exact]);
 f.used=100;f.credits=[f.credit('later',null)];for(let n=0;n<2;n++)assert.equal((await f.tick()).status,'awaitingUsageDrop');assert.equal(f.consume.length,2);
 f.readFailure=true;assert.equal((await f.tick()).status,'awaitingUsageDrop');f.readFailure=false;assert.equal(f.consume.length,2);
 f.used=24;f.spend=true;s=await f.tick();assert.equal(s.status,'armed');assert.equal(s.receipts.length,1);assert.equal(f.consume.length,2);f.spend=false;
 // A short allowance window at 99% is eligible, even immediately before reset.
 f.extraWindow={usedPercent:99,windowDurationMins:300,resetsAt:Math.floor(Date.now()/1000)+1};s=await f.tick();assert.equal(s.status,'awaitingUsageDrop');assert.equal(f.consume.length,3);assert.equal(f.consume[2].creditId,'later');assert.notEqual(f.consume[2].idempotencyKey,exact.idempotencyKey);
});

test('standing native runner waits through missing bank, account/cap stops and negative outcomes without churn',async t=>{
 const f=await fixture(t);f.used=100;
 for(const credits of [[],[f.credit('expired',1)]]){f.credits=credits;assert.equal((await f.tick()).status,'armed');}assert.equal(f.consume.length,0);
 f.credits=[f.credit('first')];
 for(const [key,value] of [['accountId','other'],['spend',true],['individual',{remainingPercent:0}],['individual',{}],['foreignBucket',true],['foreignDefault',true],['used',101],['used',null]]){
  const previous=f[key];f[key]=value;await f.tick();assert.equal(f.consume.length,0,key);f[key]=previous;
 }
 let calls=f.calls.length;await f.tick({status:'paused'});assert.equal(f.calls.length,calls);await f.tick({status:'armed',enabled:false});assert.equal(f.calls.length,calls);
 const at=Math.floor(Date.now()/1000);f.credits=[f.credit('first',at+1000),f.credit('also-banked',at+2000),f.credit('third-banked',at+3000)];
 f.outcome='noCredit';let s=await f.tick({enabled:true});assert.equal(s.status,'armed');assert.equal(f.consume.length,1);await f.tick();f.credits.reverse();await f.tick();assert.equal(f.consume.length,1,'unchanged multi-credit inventory cannot acquire new keys');
 f.credits.push(f.credit('new'));f.outcome='nothingToReset';s=await f.tick();assert.equal(s.status,'armed');assert.equal(f.consume.length,2);assert.equal(f.consume[1].creditId,'new');await f.tick();assert.equal(f.consume.length,2,'unchanged no-reset snapshot cannot churn keys');
 f.used=99;f.outcome='reset';s=await f.tick();assert.equal(s.status,'awaitingUsageDrop');assert.equal(f.consume.length,3);assert.equal(f.consume[2].creditId,'new','old rejected inventory cannot reenter after another negative outcome');assert.notEqual(f.consume[1].idempotencyKey,f.consume[2].idempotencyKey);
});
