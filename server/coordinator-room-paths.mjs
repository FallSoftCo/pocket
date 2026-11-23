/** Narrow human room routes shared by local admission and paired-source egress. */
export function coordinatorRoomPath(path,method='GET'){
 if(typeof path!=='string'||path.includes('#')||path.includes('://')||path.includes('..'))return false;
 if(method==='POST')return /^\/api\/hollywood\/v3\/rooms(?:\/[0-9a-f-]{36}\/messages)?$/.test(path);
 if(method!=='GET')return false;
 if(/^\/api\/hollywood\/v3\/(?:identity|rooms(?:\/[0-9a-f-]{36})?|commands\/[A-Za-z0-9_:-]{1,128})$/.test(path))return true;
 if(!/^\/api\/hollywood\/v3\/rooms\/[0-9a-f-]{36}\/history\?/.test(path))return false;
 const q=new URLSearchParams(path.slice(path.indexOf('?')+1)),keys=[...q.keys()];
 return new Set(keys).size===keys.length&&keys.every(k=>['view','limit','before'].includes(k))&&q.get('view')==='conversation'&&
  /^[1-9][0-9]?$/.test(q.get('limit')||'')&&Number(q.get('limit'))<=30&&
  (!q.has('before')||/^[1-9][0-9]{0,15}$/.test(q.get('before'))&&Number.isSafeInteger(Number(q.get('before'))));
}
