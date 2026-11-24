import test from 'node:test';
import assert from 'node:assert/strict';
import {ProviderUsage} from '../server/provider-usage.mjs';

const response=(body,ok=true)=>({ok,json:async()=>body});

test('DeepSeek balance is cached, force-refreshable and never exposes its credential',async()=>{
 let calls=0,updates=0;
 const usage=new ProviderUsage({apiKey:'private-deepseek-key',fetchImpl:async(url,options)=>{calls++;assert.equal(url,'https://api.deepseek.com/user/balance');assert.equal(options.redirect,'error');return response({is_available:true,balance_infos:[{currency:'CNY',total_balance:'1.00'},{currency:'USD',total_balance:'9.96'}]});},onUpdate:()=>updates++});
 const first=await usage.refresh();assert.deepEqual(first,{providerId:'deepseek',configured:true,available:true,currency:'USD',totalBalance:'9.96',updatedAt:first.updatedAt,stale:false,state:'available'});assert.equal(calls,1);assert.equal(updates,1);
 await usage.refresh();assert.equal(calls,1);
 await usage.refresh({force:true});assert.equal(calls,2);
 assert(!JSON.stringify(first).includes('private-deepseek-key'));
});

test('missing configuration performs no network request',async()=>{
 let calls=0;const usage=new ProviderUsage({apiKey:'',fetchImpl:async()=>{calls++;return response({});}});
 assert.equal(usage.snapshot(),null);assert.equal(await usage.refresh(),null);assert.equal(calls,0);
});

test('invalid and failed responses retain only sanitized last-known balance state',async()=>{
 let fail=false;
 const usage=new ProviderUsage({apiKey:'private-deepseek-key',clock:()=>1000,fetchImpl:async()=>fail?{ok:false,json:async()=>({})}:response({is_available:true,balance_infos:[{currency:'USD',total_balance:'2.50'}]})});
 assert.equal((await usage.refresh()).totalBalance,'2.50');fail=true;
 const stale=await usage.refresh({force:true});assert.equal(stale.available,true);assert.equal(stale.stale,true);assert.equal(stale.state,'stale');assert.equal(stale.totalBalance,'2.50');
 assert(!JSON.stringify(stale).includes('private-deepseek-key'));
});

test('unknown currencies and malformed balances are not projected',async()=>{
 const usage=new ProviderUsage({apiKey:'private-deepseek-key',fetchImpl:async()=>response({balance_infos:[{currency:'usd',total_balance:'9.96'}]})});
 const value=await usage.refresh();assert.equal(value.available,false);assert.equal(value.totalBalance,null);assert.equal(value.state,'unavailable');
});
