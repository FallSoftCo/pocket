import {recordingStore} from './storage.mjs';
const config=await (await fetch(new URL('web/config',import.meta.url))).json();
window.addEventListener('message',async event=>{
 if(!config.migrationTargetOrigin||event.origin!==config.migrationTargetOrigin||event.source!==parent||parent===window||event.data?.type!=='nextcomp-import-request'||typeof event.data.nonce!=='string')return;
 const entries={};let bytes=0;for(const key of Object.keys(localStorage)){if(key!=='nextcomp.web.theme'&&!key.startsWith('nextcomp.web.v1.'))continue;const value=localStorage.getItem(key);if(value.length>100000||bytes+value.length>4*1024*1024)continue;entries[key]=value;bytes+=value.length;}
 let recording;try{recording=await recordingStore();}catch{}
 if(recording?.blob?.size>12*1024*1024)recording=null;
 parent.postMessage({type:'nextcomp-import-state',nonce:event.data.nonce,entries,recording},config.migrationTargetOrigin);
});
parent.postMessage({type:'nextcomp-import-ready'},config.migrationTargetOrigin);
