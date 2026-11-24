import test from 'node:test';
import assert from 'node:assert/strict';
import {PendingSends} from '../web/pending-sends.mjs';

function storage(){
 const values=new Map();
 return {values,read:(key,fallback=null)=>values.has(key)?JSON.parse(values.get(key)):fallback,write:(key,value)=>values.set(key,JSON.stringify(value))};
}

test('saved sends migrate to a per-scope collection while preserving the latest legacy receipt',()=>{
 const local=storage(),parent={id:'parent-send',scope:'parent-123',deviceId:'browser-fixture',text:'Parent receipt',state:'unknown',createdAt:1};
 local.write('pending',parent);
 const sends=new PendingSends(local);
 assert.deepEqual(sends.all().map(row=>row.id),['parent-send']);
 assert.equal(JSON.parse(local.values.get('pending')).id,'parent-send');

 sends.save({id:'other-send',scope:'other-123',deviceId:'browser-fixture',text:'Other message',state:'prepared',createdAt:2});
 assert.deepEqual(sends.forScope('parent-123','browser-fixture').map(row=>row.id),['parent-send']);
 assert.deepEqual(sends.forScope('other-123','browser-fixture').map(row=>row.id),['other-send']);
 assert.equal(JSON.parse(local.values.get('pending')).id,'other-send');

 sends.remove(parent);
 assert.deepEqual(sends.all().map(row=>row.id),['other-send'],'removing one scope must not discard another saved send');
 sends.remove('other-send');
 assert.deepEqual(sends.all(),[]);
 assert.equal(JSON.parse(local.values.get('pending')),null);
});
