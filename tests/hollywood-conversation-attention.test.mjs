import test from 'node:test';
import assert from 'node:assert/strict';
import {hollywoodRequest} from '../server/hollywood.mjs';
const id='11111111-1111-4111-8111-111111111111';
const common={authorityId:id,attentionId:id,agentId:id,roomId:id,nativeThread:'original-thread',nativeTurn:'original-turn',requestDigest:'a'.repeat(64)};
const request=scope=>({operationId:'original-answer',scope,response:{answers:{choice:{answers:['Exact choice']}}}});
const route=scope=>hollywoodRequest('POST',`attention/${id}/response`,{},request(scope));
test('gateway preserves both native callback scope variants and refuses mixed scopes',()=>{
 const conversation={...common,conversationVersion:1,sourceEventId:id};
 assert.deepEqual(route(conversation).body,request(conversation));
 const legacy={...common,deliveryId:id,hostId:id,generation:3};
 assert.deepEqual(route(legacy).body,request(legacy));
 for(const bad of [{...conversation,deliveryId:id},{...conversation,sourceEventId:'missing'},
  {...conversation,conversationVersion:2},{...legacy,generation:0},{...conversation,nativeTurn:''}]){
  assert.throws(()=>route(bad),e=>e.status===400);
 }
});
