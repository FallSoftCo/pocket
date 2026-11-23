import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {isIP} from 'node:net';
const execute=promisify(execFile);
const loopback=address=>['127.0.0.1','::1','::ffff:127.0.0.1'].includes(address);
export function tailnetAddress(value){
 if(typeof value!=='string')return null;
 // Serve appends the authenticated source; never trust a supplied first hop.
 const address=value.split(',').at(-1).trim();
 if(isIP(address)===4){const [a,b]=address.split('.').map(Number);return a===100&&b>=64&&b<=127?address:null;}
 return isIP(address)===6&&address.toLowerCase().startsWith('fd7a:115c:a1e0:')?address:null;
}
export function createTailnetBrowserAuth({backend,ownerToken,whois=async address=>JSON.parse((await execute('tailscale',['whois','--json',address],{timeout:3000,maxBuffer:262144})).stdout),request=fetch,clock=Date.now}){
 if(!ownerToken)throw Error('Tailnet sign-in requires its private provisioning credential.');
 const target=new URL(backend);
 if(!['127.0.0.1','localhost','[::1]'].includes(target.hostname))throw Error('Tailnet provisioning backend must be loopback.');
 let attempts=[];
 return async req=>{
  if(!loopback(req.socket.remoteAddress))throw Error('Tailnet sign-in requires the local Serve proxy.');
  const address=tailnetAddress(req.headers['x-forwarded-for']);
  if(!address)throw Error('Open NextComp through its private Tailscale address.');
  const identity=await whois(address);
  if(!identity?.Node?.ID||!identity.Node.Addresses?.some(cidr=>cidr.split('/')[0]===address))throw Error('Tailscale could not verify this device.');
  const login=req.headers['tailscale-user-login'];
  if(login&&login!==identity.UserProfile?.LoginName)throw Error('Tailscale identity did not match this connection.');
  const now=clock();attempts=attempts.filter(t=>now-t<60000);
  if(attempts.length>=6)throw Error('Too many new browser sign-ins. Try again in one minute.');
  attempts.push(now);
  const post=async(path,body,token)=>{
   const response=await request(new URL(path,target),{method:'POST',redirect:'error',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body),signal:AbortSignal.timeout(8000)});
   if(!response.ok)throw Error('Browser sign-in is temporarily unavailable. Existing pairings are unchanged.');
   return response.json();
  };
  const pairing=await post('/api/pairing',{},ownerToken);
  const node=String(identity.Node.Name||'device').replace(/\.$/,'').split('.')[0].slice(0,60);
  const paired=await post('/api/pair',{code:pairing.code,name:`Tailnet browser · ${node}`});
  if(!/^[a-f0-9]{64}$/.test(paired.token)||typeof paired.id!=='string')throw Error('Invalid browser sign-in response.');
  return {id:paired.id,host:paired.host,token:paired.token};
 };
}
