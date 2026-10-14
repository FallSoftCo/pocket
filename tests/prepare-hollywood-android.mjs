/** Provision only disposable rooms and local QA processes; preserve every original intent. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';

process.umask(0o077);
const workroot=resolve(process.argv[2]),root=resolve(workroot,'repair-release-20261014/android-native-room-qualification');
const priorRoot=resolve(workroot,'repair-release-20261014/room-activity-native-qualification');
const gatewayRoot=resolve(workroot,'repair-release-20261014/nextcomp-native-gateway-qualification');
const save=(name,value)=>writeFileSync(resolve(root,name),JSON.stringify(value,null,2),{mode:0o600});
if(existsSync(resolve(root,'environment-intent.json')))throw Error('Original Android environment intent exists; inspect accepted receipts rather than replay setup.');
const prior=JSON.parse(readFileSync(resolve(priorRoot,'scenario-intent.json')));
const install=JSON.parse(readFileSync(resolve(priorRoot,'installation/installation-intent.json')));
const gateway=JSON.parse(readFileSync(resolve(gatewayRoot,'scenario-intent.json')));
const ids={roomA:randomUUID(),roomB:randomUUID()};save('environment-intent.json',{ids,gatewayRoot,priorRoot});
const receipts=[];
async function api(method,path,body){
 const response=await fetch('http://127.0.0.1:18768/hollywood/v3/'+path,{method,headers:{Authorization:`Bearer ${install.credentials.Owner.token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
 const result=await response.json();receipts.push({method,path,status:response.status,result});save('private-setup-receipts.json',receipts);
 assert.equal(response.status,200,`${method} ${path}: ${response.status}`);return result;
}
for(const [name,id] of Object.entries(ids))await api('POST','rooms',{id,name:name==='roomA'?'Native UI A':'Native UI B',purpose:'Disposable actual Android integration',ownerId:prior.ids.native});
for(let n=0;n<125;n++)await api('POST',`rooms/${ids.roomA}/messages`,{operationId:`android-history:${n}`,kind:'message',audience:'room',body:n===124?'LATEST CANONICAL HISTORY':`Earlier canonical message ${n}`});
const first=await api('GET',`rooms/${ids.roomA}/history?before=9007199254740991&limit=100`);
assert.equal(first.events.length,100);assert.ok(first.olderCursor);assert.equal(first.events.at(-1).body,'LATEST CANONICAL HISTORY');
const older=await api('GET',`rooms/${ids.roomA}/history?before=${first.olderCursor}&limit=100`);
assert.equal(older.events.length,25);assert.equal(older.olderCursor,null);
const detail=await api('GET','rooms/'+ids.roomA);
assert.equal(detail.room.owner,prior.ids.native);assert.equal(detail.permissions.can_request,true);assert.ok(detail.actors.some(a=>a.id===prior.ids.native));
const data=resolve(gatewayRoot,'gateway-data');const cfg=JSON.parse(readFileSync(resolve(data,'hollywood.json')));
cfg.default={...cfg.devices[gateway.ids.owner],defaultRoomId:ids.roomA};cfg.devices[gateway.ids.owner].defaultRoomId=ids.roomA;
save('prior-gateway-registration.json',JSON.parse(readFileSync(resolve(data,'hollywood.json'))));
writeFileSync(resolve(data,'hollywood.json'),JSON.stringify(cfg,null,2),{mode:0o600});
const nativeCfg={...JSON.parse(readFileSync(resolve(priorRoot,'connector.json'))),turnDeadlineSeconds:500};
save('native-connector.json',nativeCfg);
const request="Finite Android integration: first call hollywood_ask with one question id value, header Value, text 'What value should the Android proof contain?'. Wait for the human answer. Then use one shell command to write that exact answer plus newline to android-proof.txt in the current workspace, and print the file. Report through Hollywood and stop. Do not delegate, schedule or start historical work.";
save('android-config.json',{base:'http://10.0.2.2:18886',token:gateway.tokens.owner,roomA:ids.roomA,roomB:ids.roomB,request});
save('setup-result.json',{state:'passed',historyRows:125,latestPage:100,olderPage:25,canonicalActorMetadata:true,modelsStarted:0,physicalPhone:false});
console.log(JSON.stringify({state:'prepared',roomA:ids.roomA,roomB:ids.roomB,modelsStarted:0}));
