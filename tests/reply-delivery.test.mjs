import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {ReplyDelivery} from '../server/reply-delivery.mjs';

function fixture(){
  const db=new DatabaseSync(':memory:');
  db.exec("CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,state TEXT,result TEXT,created_at INTEGER,updated_at INTEGER,mode TEXT DEFAULT 'steer')");
  db.exec("INSERT INTO outgoing VALUES('message','source','hello','queued',NULL,1,1,'steer')");
  return db;
}

test('a preflight timeout keeps the original message queued with bounded backoff',()=>{
  const db=fixture();
  try{
    const delivery=new ReplyDelivery(db,{clock:()=>1000});
    delivery.prepare('message');
    assert.equal(delivery.preflightFailed('message',{code:'CODEX_TIMEOUT'}),true);
    const row=db.prepare('SELECT state,result FROM outgoing WHERE id=?').get('message');
    assert.equal(row.state,'queued');assert.match(row.result,/has not been dispatched/);
    assert.equal(delivery.ready('message'),false);
  }finally{db.close();}
});

test('accept resolves only the durable sending intent and records the native result',()=>{
  const db=fixture();
  try{
    const delivery=new ReplyDelivery(db,{clock:()=>1000});
    delivery.prepare('message');
    db.prepare("UPDATE outgoing SET state='sending' WHERE id=?").run('message');
    const row=db.prepare('SELECT * FROM outgoing WHERE id=?').get('message');
    assert.equal(delivery.accept(row,{turnId:'turn-1'}),true);
    assert.equal(delivery.accept(row,{turnId:'turn-2'}),false);
    assert.deepEqual(JSON.parse(db.prepare('SELECT result FROM outgoing WHERE id=?').get('message').result),{turnId:'turn-1'});
    assert.equal(db.prepare('SELECT phase FROM reply_dispatch WHERE id=?').get('message').phase,'acknowledged');
  }finally{db.close();}
});
