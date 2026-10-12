import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveTimeline,timelinePage,retainUserSendIdentity} from '../server/timeline.mjs';
const user=(client)=>({id:'provider',type:'userMessage',...(client?{clientId:client}:{}),content:[{type:'text',text:'Identical words'}]});
const event=(live,item)=>live.ingest({method:'item/completed',params:{threadId:'thread',turnId:'turn',item}});
test('metadata-poor completion preserves send identity in ingress and snapshot',()=>{
 const live=new LiveTimeline();event(live,user('send'));
 assert.equal(event(live,user()).row.clientId,'send');
 const snapshot={id:'thread',turns:[{id:'turn',status:'inProgress',items:[user()]}]};
 assert.equal(timelinePage(live.merge(snapshot)).rows.find(x=>x.kind==='user').clientId,'send');
 assert.equal(live.bytes,[...live.threads.get('thread').get('turn').items.values()].reduce((n,x)=>n+x.bytes,0));
});
test('bare live item cannot overwrite snapshot identity',()=>{
 const live=new LiveTimeline();event(live,user());
 const snapshot={id:'thread',turns:[{id:'turn',status:'inProgress',items:[user('send')]}]};
 assert.equal(timelinePage(live.merge(snapshot)).rows.find(x=>x.kind==='user').clientId,'send');
});
test('retention does not join other items, kinds, threads or conflicting claims',()=>{
 assert.equal(retainUserSendIdentity(user('first'),user('second')).clientId,'second');
 assert.equal(retainUserSendIdentity(user('first'),{...user(),id:'different'}).clientId,undefined);
 assert.equal(retainUserSendIdentity(user('first'),{...user(),type:'agentMessage'}).clientId,undefined);
 const live=new LiveTimeline();event(live,user('first'));
 assert.equal(live.ingest({method:'item/completed',params:{threadId:'other-thread',turnId:'turn',item:user()}}).row.clientId,undefined);
});
