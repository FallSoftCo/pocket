import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {createAccountResetControl} from '../web/account-reset.mjs';

test('browser reset confirmation, unknown reply and reload retain one source-bound original operation',async()=>{
 const dom=new JSDOM('<body/>',{url:'https://fixture.invalid'}),old=global.document;global.document=dom.window.document;
 try{
  const calls=[];let lose=true;
  const api=async(path,body)=>{
   if(!body)return {available:true,accountToken:'synthetic-account'};
   calls.push({path,body});if(lose){lose=false;throw Error('Reply lost');}return {state:'acknowledged',message:'Reset applied.'};
  };
  const source={owner:'paired-browser',environment:'workstation',path:'/api/account/reset'};
  const build=()=>createAccountResetControl({api,storage:dom.window.localStorage})(source);
  let box=build();await box.firstChild.onclick();assert.equal(calls.length,0);
  box.lastChild.lastChild.onclick();assert.equal(calls.length,0,'cancel never redeems');
  await box.firstChild.onclick();await box.lastChild.firstChild.onclick();assert.equal(calls.length,1);
  assert.equal(box.firstChild.textContent,'Check reset result');
  box=build();await box.firstChild.onclick();assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);
  assert.equal(dom.window.localStorage.length,0);assert.match(box.textContent,/Reset applied/);
 }finally{global.document=old;dom.window.close();}
});
