import test from 'node:test';
import assert from 'node:assert/strict';
import {NativeSettingsPanel} from '../web/native-settings.mjs';

// Exercise the real open/request/change lifecycle; rendering is independent of
// admission and needs no browser account, daemon or installed DOM dependency.
function fixture(){
 let scope='env_mac__dGhyZWFkLWE',releaseVersion,releaseWrite;
 const calls=[];
 const panel=Object.assign(Object.create(NativeSettingsPanel.prototype),{
  getScope:()=>scope,serial:0,feedback:{textContent:''},
  dialog:{open:false,showModal(){this.open=true;},close(){this.open=false;}},
  load:async()=>{},
  api:async(path,body,options)=>{
   calls.push({path,body,options});
   if(body)return new Promise(resolve=>{releaseWrite=resolve;});
   return new Promise(resolve=>{releaseVersion=resolve;});
  }
 });
 return {panel,calls,setScope:value=>scope=value,version:()=>releaseVersion({configVersion:'same-native-version'}),written:()=>releaseWrite({notice:'Write acknowledged'})};
}
const app={id:'app_shared_on_both_hosts',enabled:true},view={section:'apps'};

test('a delayed configuration version cannot retarget after closing and reopening another environment',async()=>{
 const f=fixture();await f.panel.open();const pending=f.panel.change(view,app,{disabled:false});
 assert.equal(f.calls[0].options.scope,'env_mac__dGhyZWFkLWE');
 assert.match(f.calls[0].path,/threadId=env_mac__dGhyZWFkLWE$/);
 f.panel.dialog.close();f.setScope('env_phone__dGhyZWFkLWI');await f.panel.open();f.version();await pending;
 assert.equal(f.calls.length,1,'The obsolete click must not dispatch any write');
 assert.equal(f.panel.origin.scope,'env_phone__dGhyZWFkLWI');
});

test('close/reopen of the same session invalidates an old pending click',async()=>{
 const f=fixture();await f.panel.open();const pending=f.panel.change(view,app,{disabled:false});
 f.panel.dialog.close();await f.panel.open();f.version();await pending;
 assert.equal(f.calls.length,1);
});

test('an admitted write keeps the originating scope and its late receipt cannot alter a newly opened panel',async()=>{
 const f=fixture();await f.panel.open();const pending=f.panel.change(view,app,{disabled:false});
 f.version();await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(f.calls[1],{path:'/api/native/configuration',body:{threadId:'env_mac__dGhyZWFkLWE',section:'apps',id:app.id,enabled:false,expectedVersion:'same-native-version'},options:{scope:'env_mac__dGhyZWFkLWE'}});
 f.panel.dialog.close();f.setScope('env_phone__dGhyZWFkLWI');await f.panel.open();f.panel.feedback.textContent='New environment';f.written();await pending;
 assert.equal(f.panel.feedback.textContent,'New environment');assert.equal(f.calls.length,2);
});
