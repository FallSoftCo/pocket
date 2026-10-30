// Only parse metadata obtained over an existing authenticated connection.
// This module never forwards an opaque origin pairing token to a peer.
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function identity(value){if(typeof value!=='string'||!uuid.test(value))throw Error('Invalid fleet identity');return value;}
function origin(value){
 if(typeof value!=='string')throw Error('Invalid fleet origin');
 const u=new URL(value);
 if(u.protocol!=='https:'||!u.hostname||u.username||u.password||u.search||u.hash||u.pathname!=='/'||u.port==='0'||
    u.href.replace(/\/$/,'')!==value.replace(/\/$/,''))throw Error('Invalid fleet origin');
 return value.replace(/\/$/,'');
}
export function fleetDescriptor(value){
 if(!value||value.version!==1||value.sessionPath!=='/api/fleet/session'||!Array.isArray(value.members)||
    value.members.length<1||value.members.length>128)throw Error('Unsupported fleet descriptor');
 const id=identity(value.id),accountId=identity(value.accountId),nodeId=identity(value.nodeId);
 const members=value.members.map(row=>{
  if(!row||typeof row.name!=='string'||!row.name.trim()||row.name.length>100||/[\x00-\x1f\x7f]/.test(row.name))throw Error('Invalid fleet member');
  return Object.freeze({id:identity(row.id),name:row.name,origin:origin(row.origin)});
 });
 if(new Set(members.map(x=>x.id)).size!==members.length||new Set(members.map(x=>x.origin)).size!==members.length||
    !members.some(x=>x.id===nodeId))throw Error('Ambiguous fleet members');
 if(value.enrollmentPath!=null&&value.enrollmentPath!=='/api/fleet/enroll')throw Error('Invalid fleet enrollment path');
 return Object.freeze({version:1,id,accountId,nodeId,members:Object.freeze(members),sessionPath:'/api/fleet/session',enrollmentPath:value.enrollmentPath??null});
}
export function fleetSession(value,descriptor,now=Date.now()){
 if(!value||value.version!==1||value.fleetId!==descriptor.id||value.accountId!==descriptor.accountId||
    typeof value.token!=='string'||value.token.length<32||value.token.length>16384||/\s/.test(value.token)||
    !Number.isSafeInteger(value.expiresAt)||value.expiresAt<=now||value.expiresAt-now>86400000)throw Error('Fleet login unavailable or audience changed');
 const fields={version:1,fleetId:value.fleetId,accountId:value.accountId,deviceId:identity(value.deviceId),token:value.token,expiresAt:value.expiresAt};
 const session={};for(const [key,item] of Object.entries(fields))Object.defineProperty(session,key,{value:item});
 Object.defineProperty(session,'toString',{value:()=> 'FleetSession(credentials redacted)'});return Object.freeze(session);
}
export function fleetWorkIdentity({fleetId,accountId,viewerId,kind,namespace,id}){
 identity(fleetId);identity(accountId);identity(namespace);
 if(!['room','session'].includes(kind)||typeof viewerId!=='string'||!viewerId||viewerId.length>200||viewerId.includes('\0')||
    typeof id!=='string'||!id||id.length>200||id.includes('\0'))throw Error('Invalid fleet work identity');
 // Room namespace is authority, session namespace is execution node. No token/URL.
 return JSON.stringify([fleetId,accountId,viewerId,kind,namespace,id]);
}
