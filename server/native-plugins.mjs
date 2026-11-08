import {createHash,createHmac,randomUUID} from 'node:crypto';
import {supportsNativeMethod} from './native-capabilities.mjs';
import {admissionTransaction} from './human-ingress.mjs';
const fail=(message,status=409)=>Object.assign(Error(message),{status});
const text=(v,max=500)=>typeof v==='string'?v.slice(0,max):'';
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const digest=v=>createHash('sha256').update(JSON.stringify(stable(v))).digest('hex');
const safeLink=v=>{try{const u=new URL(v);return u.protocol==='https:'&&!u.username&&!u.password&&u.href.length<=4000?u.href:null;}catch{return null;}};
const opaque=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const identity=(m,p)=>{
 const remote=p.source?.type==='remote',remoteId=p.shareContext?.remotePluginId||p.remotePluginId;
 if(!text(m.name)||!text(p.id)||!text(p.name)||!['local','git','npm','remote'].includes(p.source?.type))return null;
 if(remote&&(!text(remoteId)||! /^[A-Za-z0-9_~-]{1,500}$/.test(remoteId)))return null;
 if(!remote&&(typeof m.path!=='string'||!m.path.startsWith('/')||m.path.length>4096))return null;
 return {id:p.id,marketplace:m.name,request:remote?{remoteMarketplaceName:m.name,pluginName:remoteId}:{marketplacePath:m.path,pluginName:p.name},uninstallId:remote?remoteId:p.id,resource:remote?'remote:'+remoteId:'local:'+p.id};
};
const summary=(m,p)=>({id:text(p.id),name:text(p.name),marketplace:text(m.name),installed:typeof p.installed==='boolean'?p.installed:null,enabled:typeof p.enabled==='boolean'?p.enabled:null,installPolicy:p.installPolicy,authPolicy:p.authPolicy,availability:p.availability,disabledReason:text(p.disabledReason?.message||p.disabledReason),mustShowInstallationInterstitial:p.mustShowInstallationInterstitial===true,sourceType:p.source?.type});
export const PLUGIN_EFFECTS='Installation changes this environment and may change the connected account. Codex may download code, refresh MCP and hook runtimes, open a browser on the host for authentication, and save credentials. This remote client cannot continue host authentication. Installation does not establish readiness.';
export const PLUGIN_REMOVAL='Removal changes this environment and may change the connected account. Bundled apps and their authorization remain. A failed response can follow partial changes.';
const unknown='Outcome unconfirmed. Native or account changes may have occurred. This attempt will not be replayed. Reload details and review current state before acknowledging this receipt.';

/** Durable pre-dispatch receipts, using the same SQLite/admission boundary as
 * conversation controls. No background worker, retry queue, or model activity. */
export class NativePlugins {
 constructor({db,codex,context,admissions=null}){
  Object.assign(this,{db,codex,context,admissions});this.busy=new Set();
  db.exec(`CREATE TABLE IF NOT EXISTS native_plugin_identity(id INTEGER PRIMARY KEY CHECK(id=1),secret TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS native_plugin_operations(id TEXT PRIMARY KEY,device TEXT NOT NULL,thread TEXT NOT NULL,plugin_key TEXT NOT NULL,resource TEXT NOT NULL,name TEXT NOT NULL,action TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL,result TEXT,at INTEGER NOT NULL,acknowledged_at INTEGER);
   UPDATE native_plugin_operations SET state='unknown' WHERE state='dispatching';`);
  db.prepare('INSERT OR IGNORE INTO native_plugin_identity(id,secret) VALUES(1,?)').run(randomUUID());
  this.sourceSecret=db.prepare('SELECT secret FROM native_plugin_identity WHERE id=1').get().secret;
 }
 allowed(method){if(!supportsNativeMethod(this.codex,method))throw fail('Plugin management is unavailable on this runtime. Qualified installed catalogs remain read-only.');}
 receipt(row){return {attemptId:row.id,threadId:row.thread||null,key:row.plugin_key,name:row.name,action:row.action,state:row.state,createdAt:row.at,acknowledgedAt:row.acknowledged_at,...(row.result?JSON.parse(row.result):{notice:row.state==='dispatching'?'Native operation is awaiting acknowledgement. Do not resubmit.':unknown})};}
 recent(key){return this.db.prepare('SELECT * FROM native_plugin_operations'+(key?' WHERE plugin_key=?':'')+" ORDER BY (state='dispatching' OR state='unknown' AND acknowledged_at IS NULL) DESC,at DESC,rowid DESC LIMIT 50").all(...(key?[key]:[])).map(r=>this.receipt(r));}
 receipts(resource){return this.db.prepare("SELECT * FROM native_plugin_operations WHERE resource=? ORDER BY (state='dispatching' OR state='unknown' AND acknowledged_at IS NULL) DESC,at DESC,rowid DESC LIMIT 50").all(resource).map(r=>this.receipt(r));}
 async catalog(threadId,cwd){
  this.allowed('plugin/list');
  const scope=cwd?{cwds:[cwd]}:{};
  const response=await this.codex.call('plugin/list',{...scope,forceRefetch:false});
  this.allowed('plugin/installed');
  const installed=await this.codex.call('plugin/installed',scope);
  const project=(m,p)=>{const origin=identity(m,p);return {origin,item:{...summary(m,p),...(origin?{key:digest(origin),inspectable:true}:{inspectable:false})}};};
  const entries=(response.marketplaces||[]).flatMap(m=>(m.plugins||[]).map(p=>project(m,p)));
  // Default discovery need not include every already-installed marketplace.
  // Keep existing inventory without treating its absence as an uninstall receipt.
  for(const m of installed.marketplaces||[])for(const p of m.plugins||[])if(p.installed){const entry=project(m,p);if(!entry.item.key||!entries.some(e=>e.item.key===entry.item.key))entries.push(entry);}
  return {entries,errorCount:(response.marketplaceLoadErrors?.length||0)+(installed.marketplaceLoadErrors?.length||0)};
 }
 async detail(input){try{return await this.readDetail(input);}catch(error){if(error.status)throw error;throw fail('Native plugin details are unavailable. No installation or removal was submitted by this request.',503);}}
 async readDetail({threadId,key}){
  if(!opaque(key))throw fail('Choose a plugin from this environment’s catalog.',400);
  await this.codex.connect();this.allowed('plugin/read');
  const thread=await this.context(threadId),catalog=await this.catalog(threadId,thread?.cwd),matches=catalog.entries.filter(e=>e.item.key===key);
  if(matches.length!==1)throw fail('The plugin identity is missing or ambiguous. Reload the catalog.');
  const {origin}=matches[0];this.allowed('plugin/read');
  const response=await this.codex.call('plugin/read',origin.request),p=response.plugin;
  const actual=p&&identity({name:p.marketplaceName,path:p.marketplacePath},p.summary||{});
  if(!actual||digest(actual)!==key)throw fail('Native plugin details belong to another source. Reload the catalog.');
  const item=summary({name:p.marketplaceName},p.summary);
  const detail={...item,key,developer:text(p.summary.interface?.developerName),capabilities:(p.summary.interface?.capabilities||[]).map(v=>text(v)),links:Object.fromEntries(['websiteUrl','privacyPolicyUrl','termsOfServiceUrl'].map(k=>[k,safeLink(p.summary.interface?.[k])]).filter(([,v])=>v)),description:text(p.description,8000),skills:(p.skills||[]).map(s=>text(s.name)).filter(Boolean),hooks:(p.hooks||[]).map(h=>text(h.eventName)).filter(Boolean),apps:(p.apps||[]).map(a=>({id:text(a.id),name:text(a.name)})),mcpServers:(p.mcpServers||[]).map(s=>text(s)).filter(Boolean)};
  const policyValid=['AVAILABLE','NOT_AVAILABLE','INSTALLED_BY_DEFAULT'].includes(item.installPolicy)&&['ON_INSTALL','ON_USE'].includes(item.authPolicy)&&['AVAILABLE','DISABLED_BY_ADMIN'].includes(item.availability)&&item.installed!==null&&item.enabled!==null&&(p.summary.mustShowInstallationInterstitial==null||typeof p.summary.mustShowInstallationInterstitial==='boolean');
  const pending=this.db.prepare("SELECT 1 FROM native_plugin_operations WHERE resource=? AND (state='dispatching' OR state='unknown' AND acknowledged_at IS NULL) LIMIT 1").get(origin.resource);
  const canInstall=supportsNativeMethod(this.codex,'plugin/install')&&policyValid&&!item.installed&&item.installPolicy!=='NOT_AVAILABLE'&&item.availability==='AVAILABLE';
  const canUninstall=supportsNativeMethod(this.codex,'plugin/uninstall')&&policyValid&&item.installed&&item.installPolicy!=='INSTALLED_BY_DEFAULT';
  return {threadId:threadId||null,detail,reviewToken:createHmac('sha256',this.sourceSecret).update(JSON.stringify(stable({threadId:threadId||null,cwd:thread?.cwd||null,origin,detail,nativeDetail:p}))).digest('hex'),canInstall:canInstall&&!pending,canUninstall:canUninstall&&!pending,blocked:!!pending,reason:pending?'A previous operation is pending or unconfirmed. Review its receipt.':!policyValid?'Native policy information is incomplete.':!canInstall&&!canUninstall?'Native installation or administrator policy does not permit this change.':'',effects:PLUGIN_EFFECTS,removalEffects:PLUGIN_REMOVAL,receipts:this.receipts(origin.resource),_origin:origin};
 }
 async mutate(body,principal){
  if(!principal?.deviceId)throw fail('An authenticated paired device is required.',403);
  const {threadId,key,reviewToken,action,attemptId}=body;
  if(!['install','uninstall','acknowledge'].includes(action)||!opaque(key)||!opaque(reviewToken)||typeof attemptId!=='string'||! /^[A-Za-z0-9_-]{8,100}$/.test(attemptId))throw fail('Invalid plugin operation.',400);
  const request=JSON.stringify({threadId:threadId||null,key,reviewToken,action});
  if(action!=='acknowledge'){
   const old=this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId);
   if(old){if(old.device!==principal.deviceId||old.request!==request)throw fail('This attempt belongs to another source or operation.');return this.receipt(old);}
  }
  await this.codex.connect();this.allowed(action==='acknowledge'?'plugin/read':`plugin/${action}`);
  const view=await this.detail({threadId,key});
  if(view.reviewToken!==reviewToken)throw fail('Plugin details or policy changed. Reload and review again.');
  if(action==='acknowledge'){
   const row=this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId);
   if(!row||row.resource!==view._origin.resource||row.state!=='unknown'||this.busy.has(row.resource)||body.confirmEffects!==true)throw fail('Only an unconfirmed receipt can be acknowledged after reviewing its current source.');
   admissionTransaction(this.db,()=>{this.admissions?.action('plugin',attemptId,'acknowledge',principal);this.db.prepare('UPDATE native_plugin_operations SET acknowledged_at=? WHERE id=?').run(Date.now(),attemptId);});
   return this.receipt(this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId));
  }
  if(body.confirmEffects!==true||action==='install'&&view.detail.mustShowInstallationInterstitial&&body.confirmInterstitial!==true)throw fail('Review and confirm this plugin’s installation effects.');
  if(!(action==='install'?view.canInstall:view.canUninstall))throw fail(view.reason||'This action is not currently offered.');
  const resource=view._origin.resource;
  if(this.busy.has(resource))throw fail('Another plugin operation is running.');
  this.allowed(`plugin/${action}`);
  // No await between final receipt admission and dispatch. Other devices and
  // application restarts cannot turn a lost response into a duplicate operation.
  admissionTransaction(this.db,()=>{
   const old=this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId);
   if(old)throw fail('This attempt was already admitted. Reload its receipt.');
   if(this.db.prepare("SELECT 1 FROM native_plugin_operations WHERE resource=? AND (state='dispatching' OR state='unknown' AND acknowledged_at IS NULL)").get(resource))throw fail('Inspect the existing plugin receipt before another change.');
   this.admissions?.record('plugin',attemptId,principal);
   this.db.prepare("INSERT INTO native_plugin_operations(id,device,thread,plugin_key,resource,name,action,request,state,at) VALUES(?,?,?,?,?,?,?,?,'dispatching',?)").run(attemptId,principal.deviceId,threadId||'',key,resource,view.detail.name,action,request,Date.now());
  });
  this.busy.add(resource);
  try{
   const result=await this.codex.call(`plugin/${action}`,action==='install'?{...view._origin.request,installAttemptId:attemptId}:{pluginId:view._origin.uninstallId});
   if(!result||typeof result!=='object'||action==='install'&&(!['ON_INSTALL','ON_USE'].includes(result.authPolicy)||!Array.isArray(result.appsNeedingAuth)))throw Error('Invalid native acknowledgement');
   const apps=action==='install'?result.appsNeedingAuth.map(a=>({id:text(a.id),name:text(a.name)})):[];
   const safe={notice:action==='uninstall'?'Removal acknowledged. Reload to inspect current state. Bundled apps and their existing authorization are unchanged.':`Installation acknowledged. ${apps.length?'Some apps need authentication. ':''}Host authentication may still be pending. Readiness is unverified.`,readiness:'unverified',...(action==='install'?{authPolicy:result.authPolicy,appsNeedingAuth:apps}:{})};
   this.db.prepare("UPDATE native_plugin_operations SET state='acknowledged',result=? WHERE id=?").run(JSON.stringify(safe),attemptId);
  }catch{
   // Even a native RPC error may follow account/config effects; never promise rollback.
   this.db.prepare("UPDATE native_plugin_operations SET state='unknown',result=NULL WHERE id=?").run(attemptId);
  }finally{this.busy.delete(resource);}
  return this.receipt(this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId));
 }
}
