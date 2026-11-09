import {createHash,createHmac,randomUUID} from 'node:crypto';
import {supportsNativeMethod} from './native-capabilities.mjs';
import {admissionTransaction} from './human-ingress.mjs';
const fail=(message,status=409)=>Object.assign(Error(message),{status,mcpAuth:true});
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const id=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,150}$/.test(value);
const serverName=value=>typeof value==='string'&&value.length>0&&value.length<=200&&value===value.trim()&&!/[\u0000-\u001f\u007f]/u.test(value);
const loginId=value=>typeof value==='string'&&value.length>0&&value.length<=256&&!/[\u0000-\u001f\u007f]/u.test(value);
const unresolved="state IN ('dispatching','pending','unknown')";
/** Explicit host OAuth only. No automatic launch, replay, token handling or model work. */
export class NativeMcpAuth {
 constructor({db,codex,admissions,context,accountEvidence=()=>null}){
  Object.assign(this,{db,codex,admissions,context,accountEvidence});this.links=new Map();this.events=new Map();this.secret=randomUUID();
  db.exec(`CREATE TABLE IF NOT EXISTS native_mcp_auth(id TEXT PRIMARY KEY,device TEXT NOT NULL,thread TEXT NOT NULL,name TEXT NOT NULL,binding TEXT NOT NULL,epoch TEXT NOT NULL,state TEXT NOT NULL,login_id TEXT,native_outcome TEXT,at INTEGER NOT NULL);
   UPDATE native_mcp_auth SET state='unknown' WHERE state IN ('dispatching','pending');`);
  const boundary=()=>{this.epoch=randomUUID();this.boundaryAt=Date.now();this.links.clear();this.events.clear();db.exec("UPDATE native_mcp_auth SET state='unknown' WHERE state IN ('dispatching','pending')");};
  boundary();codex.on('connected',boundary);codex.on('disconnected',boundary);
  codex.on('event',event=>{
   if(['account/updated','account/login/completed'].includes(event.method)){boundary();return;}
   if(event.method!=='mcpServer/oauthLogin/completed')return;
   const p=event.params;if(!loginId(p?.loginId)||typeof p.success!=='boolean')return;
   this.events.set(p.loginId,{name:p.name,threadId:p.threadId??null,success:p.success,epoch:this.epoch});
   while(this.events.size>32)this.events.delete(this.events.keys().next().value);
   const row=db.prepare("SELECT * FROM native_mcp_auth WHERE login_id=? AND state IN ('pending','acknowledged')").get(p.loginId);if(row)this.finish(row);
  });
 }
 binding(){const e=this.accountEvidence(this.boundaryAt);return typeof e?.accountId==='string'&&e.accountId?hash(e.accountId):null;}
 capture(){return {epoch:this.epoch,transport:this.codex.transport,binding:this.binding()};}
 check(s){if(!this.codex.ready||s.epoch!==this.epoch||s.transport!==this.codex.transport||!s.binding||s.binding!==this.binding())throw fail('Native connection or account changed. Reload MCP sign-in.');}
 token(value){return createHmac('sha256',this.secret).update(JSON.stringify(value)).digest('hex');}
 finish(row){const e=this.events.get(row.login_id);if(!e||e.epoch!==row.epoch||e.name!==row.name||e.threadId!==(row.thread||null)||row.binding!==this.binding())return;
  this.db.prepare('UPDATE native_mcp_auth SET state=?,native_outcome=? WHERE id=?').run(row.state==='acknowledged'?'acknowledged':e.success?'completed':'failed',e.success?'completed':'failed',row.id);this.links.delete(row.id);this.events.delete(row.login_id);
 }
 receipt(row,device,threadId=row.thread||null){const notices={dispatching:'Waiting for the original native reply. Do not start another sign-in.',pending:'Sign-in started. Complete authorization on the original host; its callback may be host-local. Reload this receipt afterward.',unknown:'Sign-in outcome is unconfirmed. Inspect authentication on the original host. This attempt is never replayed.',acknowledged:'Uncertain sign-in inspected and dismissed. The original flow was not cancelled or replayed; it may still complete on the host.',completed:'Native sign-in completed. Reload MCP status to inspect tool readiness.',failed:'Native sign-in failed. Inspect the original host before starting another attempt.'};
  return {attemptId:row.id,name:row.name,threadId:row.thread||null,state:row.state,nativeOutcome:row.native_outcome||null,notice:notices[row.state]+(row.state==='acknowledged'&&row.native_outcome?' Native later reported '+row.native_outcome+'.':''),canAcknowledge:this.codex.ready&&['unknown','pending'].includes(row.state)&&row.binding===this.binding()&&this.codex.operationCapacityAvailable(),...(row.thread===(threadId||'')&&row.device===device&&row.epoch===this.epoch&&row.binding===this.binding()&&row.state==='pending'&&this.links.has(row.id)?{authorizationUrl:this.links.get(row.id)}:{})};
 }
 recent(name,threadId,device){return this.db.prepare(`SELECT * FROM native_mcp_auth WHERE name=? AND (binding=? OR (thread=? AND device=?)) ORDER BY ${unresolved} DESC,at DESC,rowid DESC LIMIT 20`).all(name,this.binding(),threadId||'',device).map(row=>this.receipt(row,device,threadId));}
 async detail(input,device){try{return await this.readDetail(input,device);}catch(error){if(error.mcpAuth)throw error;throw fail('Native MCP sign-in inspection unavailable. No sign-in was submitted.',503);}}
 async readDetail({name,threadId},device){
  if(!serverName(name)||threadId!=null&&!id(threadId))throw fail('Choose a configured MCP server.',400);
  await this.codex.connect();if(!supportsNativeMethod(this.codex,'mcpServer/oauth/login'))throw fail('MCP sign-in is unavailable on this runtime.');
  const snapshot=this.capture();this.check(snapshot);const thread=await this.context(threadId);this.check(snapshot);
  const config=await this.codex.call('config/read',{includeLayers:true,...(thread?.cwd?{cwd:thread.cwd}:{})});this.check(snapshot);
  const servers=config.config?.mcp_servers;const server=servers&&Object.hasOwn(servers,name)?servers[name]:null;
  if(!server||typeof server.url!=='string'||server.enabled===false||server.bearer_token_env_var)throw fail('This configured server does not offer this OAuth flow.');
  const origin={name,threadId:threadId||null,cwd:thread?.cwd||null,config:hash(config.config),binding:snapshot.binding,epoch:snapshot.epoch};
  const status=await this.codex.call('mcpServerStatus/list',{...(threadId?{threadId}:{}),detail:'toolsAndAuthOnly',limit:100});this.check(snapshot);
  const observed=status.data?.find(s=>s.name===name);
  const blocked=!!this.db.prepare(`SELECT 1 FROM native_mcp_auth WHERE name=? AND binding=? AND ${unresolved}`).get(name,snapshot.binding);
  return {name,threadId:threadId||null,observedAuthStatus:observed?.authStatus||'unknown',canStart:!blocked,reviewToken:this.token(origin),receipts:this.recent(name,threadId,device),notice:'Starting sign-in contacts this server’s authorization provider and may save credentials on the original host. Complete its host-local callback there. No tools or model tasks are started.',_snapshot:snapshot};
 }
 async acknowledge({attemptId,name,threadId,confirmInspected},principal){
  if(principal?.kind!=='paired-device'||!principal.deviceId||principal.deviceId==='owner')throw fail('MCP sign-in requires its original paired device.',403);
  if(confirmInspected!==true)throw fail('Inspect the original host and acknowledge that another start can duplicate an unfinished flow.',400);
  const row=this.db.prepare('SELECT * FROM native_mcp_auth WHERE id=?').get(attemptId);
  if(!row||row.name!==name||row.thread!==(threadId||''))throw fail('Choose the original sign-in receipt.');
  if(!this.receipt(row,principal.deviceId).canAcknowledge)throw fail('This sign-in cannot be dismissed while its native reply remains outstanding.');
  admissionTransaction(this.db,()=>{this.admissions?.action('mcp-oauth',attemptId,'inspected-uncertain-flow',principal);this.db.prepare("UPDATE native_mcp_auth SET state='acknowledged' WHERE id=?").run(attemptId);});this.links.delete(attemptId);
  return this.receipt({...row,state:'acknowledged'},principal.deviceId);
 }
 async start(input,principal){
  if(principal?.kind!=='paired-device'||!principal.deviceId||principal.deviceId==='owner')throw fail('MCP sign-in requires its original paired device.',403);
  const {name,threadId,attemptId,reviewToken,confirmHostAuthentication}=input;
  if(!id(attemptId)||confirmHostAuthentication!==true)throw fail('Review and confirm host authentication.',400);
  const old=this.db.prepare('SELECT * FROM native_mcp_auth WHERE id=?').get(attemptId);
  if(old){if(old.device!==principal.deviceId||old.name!==name||old.thread!==(threadId||''))throw fail('Attempt identity changed.');return this.receipt(old,principal.deviceId);}
  const view=await this.detail({name,threadId},principal.deviceId);
  if(!view.canStart||view.reviewToken!==reviewToken)throw fail('MCP source changed or another sign-in is unresolved. Reload its receipt.');
  if(!this.codex.operationCapacityAvailable())throw fail('Awaiting an original native operation reply.');
  admissionTransaction(this.db,()=>{this.check(view._snapshot);if(this.db.prepare(`SELECT 1 FROM native_mcp_auth WHERE name=? AND binding=? AND ${unresolved}`).get(name,view._snapshot.binding))throw fail('Another sign-in is unresolved.');this.admissions?.record('mcp-oauth',attemptId,principal);this.db.prepare("INSERT INTO native_mcp_auth(id,device,thread,name,binding,epoch,state,at) VALUES(?,?,?,?,?,?,'dispatching',?)").run(attemptId,principal.deviceId,threadId||'',name,view._snapshot.binding,view._snapshot.epoch,Date.now());});
  const complete=({result,error,terminal})=>{
   const row=this.db.prepare('SELECT * FROM native_mcp_auth WHERE id=?').get(attemptId);
   try{
    this.check(view._snapshot);if(error||!terminal||!loginId(result?.loginId))throw Error();
    let url;try{const parsed=new URL(result.authorizationUrl);if(parsed.protocol==='https:'&&!parsed.username&&!parsed.password)url=parsed.href;}catch{}
    this.db.prepare("UPDATE native_mcp_auth SET state='pending',login_id=? WHERE id=?").run(result.loginId,attemptId);if(url)this.links.set(attemptId,url);this.finish({...row,state:'pending',login_id:result.loginId});
   }catch{this.db.prepare("UPDATE native_mcp_auth SET state='unknown' WHERE id=?").run(attemptId);this.links.delete(attemptId);}
  };
  try{await this.codex.callOperation('mcpServer/oauth/login',{name,...(threadId?{threadId}:{}),timeoutSecs:300},complete);}catch{}
  return this.receipt(this.db.prepare('SELECT * FROM native_mcp_auth WHERE id=?').get(attemptId),principal.deviceId);
 }
}
