import {readFileSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHmac} from 'node:crypto';
import {spawn} from 'node:child_process';

// The CLI and timer share flock and the same fsynced policy/attempt journal.
// No credentials or provider credit identifiers are sent to clients.
export function registerAccountResetRoutes(app,{dir,root,secrets,rateLimits,route,admissions,invoke=invokeReset}){
 const path=process.env.POCKET_RESET_STATE||resolve(dir,'account-reset-policy.json');
 const lock=process.env.POCKET_RESET_LOCK||resolve(dir,'account-reset.lock');
 const read=()=>{try{return JSON.parse(readFileSync(path,'utf8'));}catch{return null;}};
 const token=account=>createHmac('sha256',secrets.adminToken).update('banked-reset:'+account).digest('base64url');
 const supported=()=>process.platform==='linux'&&existsSync('/usr/bin/flock')&&!!rateLimits.codex.socket&&!rateLimits.codex.command;
 const evidence=async()=>{
  await rateLimits.refresh({force:true});
  const state=read(),account=rateLimits.accountId;
  if(!supported()||!state||state.schemaVersion!==2||state.mode!=='standing')return {available:false,message:'Manual reset is not configured on this environment. Open the account on its reset-owning workstation.'};
  if(rateLimits.failed||!account||account!==state.accountId)return {available:false,message:'The signed-in account could not be verified. Refresh before using a reset.'};
  return {available:true,accountToken:token(account),accountId:account,state};
 };
 app.get('/api/account/reset',route(async(req,res)=>{
  const check=await evidence();if(!check.available)return res.json(check);
  const requests=Object.entries(check.state.manualRequests||{}).filter(([,r])=>r.deviceId===req.device.id);
  const pending=requests.find(([,r])=>!r.result);
  const latest=requests.sort((a,b)=>(b[1].updatedAt||b[1].createdAt)-(a[1].updatedAt||a[1].createdAt))[0];
  res.json({available:true,accountToken:check.accountToken,automatic:check.state.enabled===true,
   pendingId:pending?.[0]||null,lastResult:latest?.[1].result||null,usage:rateLimits.snapshot()});
 }));
 app.post('/api/account/reset',route(async(req,res)=>{
  const {id,accountToken}=req.body||{};
  if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{16,100}$/.test(id))return res.status(400).json({error:'A stable reset request identifier is required.'});
  const check=await evidence();
  if(!check.available||accountToken!==check.accountToken)return res.status(409).json({error:check.message||'The account changed. Refresh before using a reset.'});
  admissions.record('account-reset',id,req.humanPrincipal);
  const result=await invoke({root,path,lock,socket:rateLimits.codex.socket,request:{id,accountId:check.accountId,deviceId:req.device.id}});
  await rateLimits.refresh({force:true});
  res.json({...result,usage:rateLimits.snapshot()});
 }));
}
export function invokeReset({root,path,lock,socket,request}){
 return new Promise((resolveResult,reject)=>{
  const child=spawn('/usr/bin/flock',['-w','1',lock,process.execPath,resolve(root,'scripts/account-reset.mjs'),'--manual'],{
   env:{...process.env,POCKET_RESET_STATE:path,CODEX_SOCKET:socket},stdio:['pipe','pipe','pipe']});
  let output='';child.stdout.on('data',chunk=>{if(output.length<65536)output+=chunk;});child.stderr.resume();
  // Runtime RPCs are bounded; never kill a dispatched consume on HTTP timeout.
  child.stdin.on('error',()=>{});child.stdin.end(JSON.stringify(request));
  child.once('error',()=>reject(Object.assign(Error('Reset runner unavailable. No new request was started.'),{status:503})));
  child.once('close',code=>{
   if(code===0){try{return resolveResult(JSON.parse(output));}catch{}}
   resolveResult({state:'pending',message:'Reset result unavailable. Check the original request before trying another reset.'});
  });
 });
}
