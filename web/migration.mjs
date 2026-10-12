import {recordingStore} from './storage.mjs';
// Cookie pairing survives a port change; storage is origin-scoped. Copy missing
// local state only, after verifying both profiles name the same backend device.
export function acceptProfile(data,deviceId,storage=localStorage){
 if(!data||typeof data.entries!=='object')return false;
 let sourceId;try{sourceId=JSON.parse(data.entries['nextcomp.web.v1.deviceId']);}catch{return false;}
 if(sourceId!==deviceId)return false;
 let bytes=0;for(const [key,value] of Object.entries(data.entries)){
  if(key!=='nextcomp.web.theme'&&!key.startsWith('nextcomp.web.v1.'))continue;
  if(typeof value!=='string'||value.length>100000||(bytes+=value.length)>4*1024*1024)continue;
  if(storage.getItem(key)===null)storage.setItem(key,value);
 }
 return true;
}
export async function importPreviousProfile(base,deviceId){
 let config;try{config=await (await fetch(new URL('web/config',base))).json();}catch{return false;}
 if(!config.migrationSourceOrigin)return false;
 const source=new URL(config.migrationSourceOrigin);if(source.protocol!=='https:'||source.hostname!==location.hostname||source.origin===location.origin)return false;
 const marker='nextcomp.web.v1.importedFrom';if(localStorage.getItem(marker)===JSON.stringify(source.origin))return false;
 return new Promise(resolve=>{
  const frame=document.createElement('iframe');frame.hidden=true;frame.title='Import previous NextComp profile';frame.src=source.origin+'/migration.html';frame.setAttribute('sandbox','allow-scripts allow-same-origin');const nonce=crypto.randomUUID();
  const finish=value=>{clearTimeout(timer);window.removeEventListener('message',receive);frame.remove();resolve(value);};
  async function receive(event){if(event.origin!==source.origin||event.source!==frame.contentWindow)return;
   if(event.data?.type==='nextcomp-import-ready'){frame.contentWindow.postMessage({type:'nextcomp-import-request',nonce},source.origin);return;}
   if(event.data?.type!=='nextcomp-import-state'||event.data.nonce!==nonce)return;
   try{if(!acceptProfile(event.data,deviceId)){finish(false);return;}if(event.data.recording?.blob instanceof Blob&&event.data.recording.blob.size<=12*1024*1024&&!(await recordingStore()))await recordingStore(event.data.recording);localStorage.setItem(marker,JSON.stringify(source.origin));finish(true);}catch{finish(false);}
  }
  const timer=setTimeout(()=>finish(false),4000);window.addEventListener('message',receive);document.body.append(frame);
 });
}
