import test from 'node:test';
import assert from 'node:assert/strict';
import {COMPUTER_USE_INSTRUCTIONS,newThreadComputerUseOptions,computerUseStatus} from '../server/computer-use.mjs';

test('computer-use instructions append existing app instructions and scope ownership to actions',()=>{
  const result=newThreadComputerUseOptions('Existing voice policy');
  assert.equal(result.developerInstructions,'Existing voice policy\n\n'+COMPUTER_USE_INSTRUCTIONS);
  assert.match(result.developerInstructions,/withLease/);
  assert.match(result.developerInstructions,/withAction/);
  assert.match(result.developerInstructions,/COMPUTER_USE_DESKTOP_RESOURCE/);
  assert.match(result.developerInstructions,/COMPUTER_USE_BROWSER_PROFILE_RESOURCE plus :page:<target>/);
  assert.match(result.developerInstructions,/worker.env/);
  assert.match(result.developerInstructions,/COMPUTER_USE_CLIENT_PATH/);
  assert.match(result.developerInstructions,/\$HOME\/.config/);
  assert.match(result.developerInstructions,/Never guess resource aliases/);
  assert.match(result.developerInstructions,/capped at 30 seconds/);
  assert.match(result.developerInstructions,/not automatically fenced/);
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
