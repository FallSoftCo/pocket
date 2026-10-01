import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

export const dataDir=resolve(process.env.POCKET_DATA||fileURLToPath(new URL('../data',import.meta.url)));
export const localUrl=process.env.POCKET_URL||`http://127.0.0.1:${process.env.PORT||18880}`;
export async function ownerRequest(path,{method='GET',body}={}){
  const url=new URL(localUrl);
  if(!['127.0.0.1','localhost','[::1]'].includes(url.hostname)||!['http:','https:'].includes(url.protocol)||url.username||url.password)throw Error('Owner commands require a loopback POCKET_URL. Run them on the workstation.');
  let adminToken;
  try{({adminToken}=JSON.parse(readFileSync(resolve(dataDir,'secrets.json'),'utf8')));}
  catch{throw Error('Start Pocket first, and use the same POCKET_DATA directory as the server.');}
  const r=await fetch(new URL(path,url),{method,redirect:'error',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(10000)});
  const result=await r.json();if(!r.ok)throw Error(result.error||`Pocket returned ${r.status}`);return result;
}
