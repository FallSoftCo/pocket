import {fixture} from './web-browser-fixture.mjs';
const row=(n,text,kind='message')=>({id:'item:'+n,key:[n,1,'i'+n],row:{id:'turn/i'+n,turnId:'turn',kind,text,version:1,_nativePosition:n,_nativeRank:1,_nativeId:'i'+n,...(kind==='user'?{_inputOrder:'canonical'}:{})}});
const entries=[row(9,'Current question, not the October cached island.','user'),row(10,'Current canonical answer follows its original question.')];
const page={thread:{id:'parent-123',name:'Prepare the field guide',status:{type:'active'}},historyHead:{epoch:'qa-one'},timeline:{native:1,entries,rows:entries.map(e=>e.row),before:null,hasEarlier:false},pending:[],outgoing:[],notes:[]};
const f=await fixture({port:18985,observationPages:{'parent-123':page}});
console.log('READY 18985');process.on('SIGTERM',async()=>{await f.close();process.exit(0)});
