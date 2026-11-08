import {PluginCatalogPages} from './native-plugin-catalog.mjs';
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
const unknown='Outcome unconfirmed. Native or account changes may have occurred. This attempt will not be replayed.';
const outstanding=" Native completion is unknown. Inventory reads cannot establish completion; further changes remain blocked. This client has no supported cancellation or completion-reconciliation operation.";
const unresolved="(state='dispatching' OR state='unknown' AND (native_terminal=0 OR acknowledged_at IS NULL))";

/** Durable pre-dispatch receipts, using the same SQLite/admission boundary as
 * conversation controls. No background worker, retry queue, or model activity. */
export class NativePlugins {
 constructor({db,codex,context,admissions=null,accountEvidence=()=>null}){
  Object.assign(this,{db,codex,context,admissions,accountEvidence});this.busy=new Set();this.generation=0;
  const boundary=()=>{this.epoch=randomUUID();this.boundaryAt=Date.now();this.generation++;this.pages?.invalidate();};
  boundary();codex.on('connected',boundary);codex.on('disconnected',boundary);
  codex.on('event',event=>{if(event.method==='account/updated'||event.method==='account/login/completed')boundary();});
  db.exec(`CREATE TABLE IF NOT EXISTS native_plugin_identity(id INTEGER PRIMARY KEY CHECK(id=1),secret TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS native_plugin_operations(id TEXT PRIMARY KEY,device TEXT NOT NULL,thread TEXT NOT NULL,plugin_key TEXT NOT NULL,resource TEXT NOT NULL,name TEXT NOT NULL,action TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL,result TEXT,at INTEGER NOT NULL,acknowledged_at INTEGER);
   UPDATE native_plugin_operations SET state='unknown' WHERE state='dispatching';`);
  const columns=new Set(db.prepare('PRAGMA table_info(native_plugin_operations)').all().map(c=>c.name));
  for(const [name,type]of [['native_terminal','INTEGER NOT NULL DEFAULT 0'],['binding','TEXT'],['native_epoch','TEXT']])if(!columns.has(name))db.exec(`ALTER TABLE native_plugin_operations ADD COLUMN ${name} ${type}`);
  db.prepare('INSERT OR IGNORE INTO native_plugin_identity(id,secret) VALUES(1,?)').run(randomUUID());
  this.sourceSecret=db.prepare('SELECT secret FROM native_plugin_identity WHERE id=1').get().secret;
  this.pages=new PluginCatalogPages({secret:this.sourceSecret,capture:()=>this.capture(),check:s=>this.check(s),load:(view,cwd,checkpoint)=>view==='browse'?this.catalog(null,cwd,checkpoint):this.installed(cwd,checkpoint)});
 }
 allowed(method){if(!supportsNativeMethod(this.codex,method))throw fail('Plugin management is unavailable on this runtime. Qualified installed catalogs remain read-only.');}
 // Account evidence comes only from the existing quota cache. Never start an
 // account/provider read as a consequence of browsing a plugin catalog.
 binding(){const evidence=this.accountEvidence(this.boundaryAt);return evidence?createHmac('sha256',this.sourceSecret).update(JSON.stringify(stable(evidence))).digest('hex'):null;}
 capture(){return {epoch:this.epoch,generation:this.generation,transport:this.codex.transport,binding:this.binding()};}
 check(snapshot){if(!this.codex.ready||snapshot.epoch!==this.epoch||snapshot.generation!==this.generation||snapshot.transport!==this.codex.transport||snapshot.binding!==this.binding())throw fail('Native connection, account evidence, or plugin operations changed. Reload and review again.');}
 receipt(row,binding=null){return {attemptId:row.id,threadId:row.thread||null,key:row.plugin_key,name:row.name,action:row.action,state:row.state,createdAt:row.at,acknowledgedAt:row.acknowledged_at,nativeTerminal:row.native_terminal===1,canAcknowledge:row.state==='unknown'&&!row.acknowledged_at&&row.native_terminal===1&&!!binding&&row.binding===binding&&row.native_epoch===this.epoch,...(row.result?JSON.parse(row.result):{notice:row.state==='dispatching'?'Native operation is awaiting acknowledgement. Do not resubmit.':unknown+(row.native_terminal===1?' Native returned a terminal error or invalid acknowledgement. Review partial effects before acknowledging this receipt.':outstanding)})};}
 recent(key){return this.db.prepare('SELECT * FROM native_plugin_operations'+(key?' WHERE plugin_key=?':'')+` ORDER BY ${unresolved} DESC,at DESC,rowid DESC LIMIT 50`).all(...(key?[key]:[])).map(r=>this.receipt(r));}
 receipts(resource,binding){return this.db.prepare(`SELECT * FROM native_plugin_operations WHERE resource=? ORDER BY ${unresolved} DESC,at DESC,rowid DESC LIMIT 50`).all(resource).map(r=>this.receipt(r,binding));}
 project(m,p){const origin=identity(m,p);return {origin,item:{...summary(m,p),...(origin?{key:digest(origin),inspectable:true}:{inspectable:false})}};}
 async installed(cwd,checkpoint=()=>{}){
  this.allowed('plugin/installed');const response=await this.codex.call('plugin/installed',cwd?{cwds:[cwd]}:{});checkpoint();
  return {entries:(response.marketplaces||[]).flatMap(m=>(m.plugins||[]).filter(p=>p.installed===true).map(p=>this.project(m,p))),errorCount:response.marketplaceLoadErrors?.length||0};
 }
 async catalogPage(input){if(input.view==='browse')this.allowed('plugin/list');return this.pages.page(input);}
 async catalog(threadId,cwd,checkpoint=()=>{}){
  this.allowed('plugin/list');
  const scope=cwd?{cwds:[cwd]}:{};
  const response=await this.codex.call('plugin/list',{...scope,forceRefetch:false});checkpoint();
  this.allowed('plugin/installed');
  const installed=await this.codex.call('plugin/installed',scope);checkpoint();
  const entries=(response.marketplaces||[]).flatMap(m=>(m.plugins||[]).map(p=>this.project(m,p))),keys=new Set(entries.map(e=>e.item.key).filter(Boolean));
  // Default discovery need not include every already-installed marketplace.
  // Keep existing inventory without treating its absence as an uninstall receipt.
  for(const m of installed.marketplaces||[])for(const p of m.plugins||[])if(p.installed){const entry=this.project(m,p);if(!entry.item.key||!keys.has(entry.item.key)){entries.push(entry);keys.add(entry.item.key);}}
  return {entries,errorCount:(response.marketplaceLoadErrors?.length||0)+(installed.marketplaceLoadErrors?.length||0)};
 }
 async detail(input){try{return await this.readDetail(input);}catch(error){if(error.status)throw error;throw fail('Native plugin details are unavailable. No installation or removal was submitted by this request.',503);}}
 async readDetail({threadId,key}){
  if(!opaque(key))throw fail('Choose a plugin from this environment’s catalog.',400);
  await this.codex.connect();this.allowed('plugin/read');
  const snapshot=this.capture(),checkpoint=()=>this.check(snapshot);
  const thread=await this.context(threadId);checkpoint();
  const catalog=await this.catalog(threadId,thread?.cwd,checkpoint),matches=catalog.entries.filter(e=>e.item.key===key);checkpoint();
  if(matches.length!==1)throw fail('The plugin identity is missing or ambiguous. Reload the catalog.');
  const {origin}=matches[0];this.allowed('plugin/read');
  const response=await this.codex.call('plugin/read',origin.request),p=response.plugin;checkpoint();
  const actual=p&&identity({name:p.marketplaceName,path:p.marketplacePath},p.summary||{});
  if(!actual||digest(actual)!==key)throw fail('Native plugin details belong to another source. Reload the catalog.');
  const item=summary({name:p.marketplaceName},p.summary);
  const detail={...item,key,developer:text(p.summary.interface?.developerName),capabilities:(p.summary.interface?.capabilities||[]).map(v=>text(v)),links:Object.fromEntries(['websiteUrl','privacyPolicyUrl','termsOfServiceUrl'].map(k=>[k,safeLink(p.summary.interface?.[k])]).filter(([,v])=>v)),description:text(p.description,8000),skills:(p.skills||[]).map(s=>text(s.name)).filter(Boolean),hooks:(p.hooks||[]).map(h=>text(h.eventName)).filter(Boolean),apps:(p.apps||[]).map(a=>({id:text(a.id),name:text(a.name)})),mcpServers:(p.mcpServers||[]).map(s=>text(s)).filter(Boolean)};
  const policyValid=['AVAILABLE','NOT_AVAILABLE','INSTALLED_BY_DEFAULT'].includes(item.installPolicy)&&['ON_INSTALL','ON_USE'].includes(item.authPolicy)&&['AVAILABLE','DISABLED_BY_ADMIN'].includes(item.availability)&&item.installed!==null&&item.enabled!==null&&(p.summary.mustShowInstallationInterstitial==null||typeof p.summary.mustShowInstallationInterstitial==='boolean');
  const pending=this.db.prepare(`SELECT 1 FROM native_plugin_operations WHERE resource=? AND ${unresolved} LIMIT 1`).get(origin.resource);
  const capacity=this.codex.operationCapacityAvailable();
  const canInstall=supportsNativeMethod(this.codex,'plugin/install')&&policyValid&&!item.installed&&item.installPolicy!=='NOT_AVAILABLE'&&item.availability==='AVAILABLE';
  const canUninstall=supportsNativeMethod(this.codex,'plugin/uninstall')&&policyValid&&item.installed&&item.installPolicy!=='INSTALLED_BY_DEFAULT';
  return {threadId:threadId||null,detail,reviewToken:createHmac('sha256',this.sourceSecret).update(JSON.stringify(stable({threadId:threadId||null,cwd:thread?.cwd||null,origin,detail,nativeDetail:p,epoch:snapshot.epoch,generation:snapshot.generation,binding:snapshot.binding}))).digest('hex'),canInstall:!!snapshot.binding&&capacity&&canInstall&&!pending,canUninstall:!!snapshot.binding&&capacity&&canUninstall&&!pending,blocked:!!pending||!capacity,reason:!snapshot.binding?'Plugin changes are read-only until existing account usage refresh provides fresh identity evidence for this connection. Check account status in the original environment, then reload.':!capacity?'Awaiting the original native plugin reply. Another plugin change cannot be submitted yet.':pending?'A previous operation is pending or unconfirmed. Review its receipt.':!policyValid?'Native policy information is incomplete.':!canInstall&&!canUninstall?'Native installation or administrator policy does not permit this change.':'',effects:PLUGIN_EFFECTS,removalEffects:PLUGIN_REMOVAL,receipts:this.receipts(origin.resource,snapshot.binding),_origin:origin,_snapshot:snapshot};
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
  this.check(view._snapshot);
  if(view.reviewToken!==reviewToken)throw fail('Plugin details or policy changed. Reload and review again.');
  if(action==='acknowledge'){
   const row=this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId);
   if(!row||row.resource!==view._origin.resource||row.state!=='unknown'||row.native_terminal!==1||!view._snapshot.binding||row.binding!==view._snapshot.binding||row.native_epoch!==this.epoch||this.busy.has(row.resource)||body.confirmEffects!==true)throw fail('Only an unconfirmed receipt can be acknowledged after reviewing its current source.');
   admissionTransaction(this.db,()=>{this.check(view._snapshot);this.admissions?.action('plugin',attemptId,'acknowledge',principal);this.db.prepare('UPDATE native_plugin_operations SET acknowledged_at=? WHERE id=?').run(Date.now(),attemptId);});this.generation++;
   return this.receipt(this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId));
  }
  if(body.confirmEffects!==true||action==='install'&&view.detail.mustShowInstallationInterstitial&&body.confirmInterstitial!==true)throw fail('Review and confirm this plugin’s installation effects.');
  if(!(action==='install'?view.canInstall:view.canUninstall))throw fail(view.reason||'This action is not currently offered.');
  const resource=view._origin.resource;
  if(this.busy.has(resource))throw fail('Another plugin operation is running.');
  this.allowed(`plugin/${action}`);
  if(!this.codex.operationCapacityAvailable())throw fail('Awaiting the original native plugin reply.');
  // No await between final receipt admission and dispatch. Other devices and
  // application restarts cannot turn a lost response into a duplicate operation.
  admissionTransaction(this.db,()=>{
   this.check(view._snapshot);
   const old=this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId);
   if(old)throw fail('This attempt was already admitted. Reload its receipt.');
   if(this.db.prepare(`SELECT 1 FROM native_plugin_operations WHERE resource=? AND ${unresolved}`).get(resource))throw fail('Inspect the existing plugin receipt before another change.');
   this.admissions?.record('plugin',attemptId,principal);
   this.db.prepare("INSERT INTO native_plugin_operations(id,device,thread,plugin_key,resource,name,action,request,state,at,binding,native_epoch) VALUES(?,?,?,?,?,?,?,?,'dispatching',?,?,?)").run(attemptId,principal.deviceId,threadId||'',key,resource,view.detail.name,action,request,Date.now(),view._snapshot.binding,view._snapshot.epoch);
  });
  this.busy.add(resource);this.generation++;
  const complete=({result,error,terminal})=>{
   try{
    if(error||!terminal||!result||typeof result!=='object'||action==='install'&&(!['ON_INSTALL','ON_USE'].includes(result.authPolicy)||!Array.isArray(result.appsNeedingAuth)))throw Error('Native outcome is uncertain');
    const apps=action==='install'?result.appsNeedingAuth.map(a=>({id:text(a.id),name:text(a.name)})):[];
    const safe={notice:action==='uninstall'?'Removal acknowledged. Reload to inspect current state. Bundled apps and their existing authorization are unchanged.':`Installation acknowledged. ${apps.length?'Some apps need authentication. ':''}Host authentication may still be pending. Readiness is unverified.`,readiness:'unverified',...(action==='install'?{authPolicy:result.authPolicy,appsNeedingAuth:apps}:{})};
    this.db.prepare("UPDATE native_plugin_operations SET state='acknowledged',native_terminal=1,result=? WHERE id=?").run(JSON.stringify(safe),attemptId);
   }catch{
    // A terminal RPC error can follow partial effects. Transport loss proves no
    // completion, and readback must never release that unknown receipt.
    this.db.prepare("UPDATE native_plugin_operations SET state='unknown',native_terminal=?,result=NULL WHERE id=?").run(terminal?1:0,attemptId);
   }finally{this.busy.delete(resource);this.generation++;}
  };
  try{await this.codex.callOperation(`plugin/${action}`,action==='install'?{...view._origin.request,installAttemptId:attemptId}:{pluginId:view._origin.uninstallId},complete);}catch{
   // The caller's deadline expires independently. Keep dispatching while the
   // live original-response observer exists; closure/restart makes it unknown.
  }
  return this.receipt(this.db.prepare('SELECT * FROM native_plugin_operations WHERE id=?').get(attemptId));
 }
}
