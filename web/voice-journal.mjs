export async function voiceJournal(action='list',value){
 const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('nextcomp-voice-journal',1);r.onupgradeneeded=()=>r.result.createObjectStore('turns',{keyPath:'id'});r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
 try{return await new Promise((resolve,reject)=>{const tx=db.transaction('turns',action==='list'?'readonly':'readwrite'),store=tx.objectStore('turns');const request=action==='list'?store.getAll():action==='delete'?store.delete(value):store.put(value);let result;request.onsuccess=()=>{result=request.result;};tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('Recording could not be saved.'));});}finally{db.close();}
}
export async function wavFromRecording(blob){
 const decode=new AudioContext();let buffer;try{buffer=await decode.decodeAudioData(await blob.arrayBuffer());}finally{await decode.close();}
 const rate=16000,frames=Math.round(buffer.duration*rate),offline=new OfflineAudioContext(1,frames,rate),source=offline.createBufferSource();source.buffer=buffer;source.connect(offline.destination);source.start();const mono=(await offline.startRendering()).getChannelData(0),bytes=new ArrayBuffer(44+mono.length*2),view=new DataView(bytes);
 const text=(at,value)=>{for(let i=0;i<value.length;i++)view.setUint8(at+i,value.charCodeAt(i));};
 text(0,'RIFF');view.setUint32(4,bytes.byteLength-8,true);text(8,'WAVE');text(12,'fmt ');view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);view.setUint32(24,rate,true);view.setUint32(28,rate*2,true);view.setUint16(32,2,true);view.setUint16(34,16,true);text(36,'data');view.setUint32(40,mono.length*2,true);
 for(let i=0;i<mono.length;i++)view.setInt16(44+i*2,Math.round(Math.max(-1,Math.min(1,mono[i]))*(mono[i]<0?32768:32767)),true);
 return new Blob([bytes],{type:'audio/wav'});
}
