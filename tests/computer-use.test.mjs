import test from 'node:test';
import assert from 'node:assert/strict';
import {COMPUTER_USE_INSTRUCTIONS,newThreadComputerUseOptions,computerUseStatus} from '../server/computer-use.mjs';

test('new browser guidance preserves other instructions and uses the single managed entry',()=>{
  const result=newThreadComputerUseOptions('Existing voice policy');
  assert.equal(result.developerInstructions,'Existing voice policy\n\n'+COMPUTER_USE_INSTRUCTIONS);
  for(const tool of ['browser_discover','browser_command','browser_status','browser_control'])assert.ok(result.developerInstructions.includes(tool));
  assert.match(COMPUTER_USE_INSTRUCTIONS,/Never add an outer manual lease/);
  assert.match(COMPUTER_USE_INSTRUCTIONS,/Existing accepted legacy tickets/);
  assert.match(COMPUTER_USE_INSTRUCTIONS,/BLD retains/);
  assert.doesNotMatch(COMPUTER_USE_INSTRUCTIONS,/client\.withLease|COMPUTER_USE_BROKER_TOKEN|worker\.env/);
});
test('unconfigured broker does not make a request; unavailable status cannot disclose secrets',async()=>{
  let calls=0;
  assert.deepEqual(await computerUseStatus({env:{},fetchImpl:()=>{calls++;}}),{configured:false,available:false,enforcement:'cooperative',reason:'not_configured'});
  assert.equal(calls,0);
  const env={COMPUTER_USE_BROKER_TOKEN:'private-token'};
  const status=await computerUseStatus({env,fetchImpl:()=>{throw Error('private-token and upstream details');}});
  assert.equal(status.available,false);assert.equal(JSON.stringify(status).includes('private-token'),false);
});
test('broker status forwards only safe aggregate counts',async()=>{
  let sent;
  const status=await computerUseStatus({env:{COMPUTER_USE_BROKER_TOKEN:'private-token'},fetchImpl:async(url,options)=>{
    sent={url:String(url),options};
    return new Response(JSON.stringify({counts:{leases:2,queued:3,quarantined:-1},token:'private-token',leases:[{owner:'private-owner'}]}));
  }});
  assert.equal(sent.url,'http://127.0.0.1:9340/v1/status');
  assert.equal(sent.options.headers.Authorization,'Bearer private-token');
  assert.deepEqual(status,{configured:true,available:true,enforcement:'cooperative',activeLeases:2,queuedRequests:3});
});
