import {randomUUID} from 'node:crypto';
import {NativeMcpReload} from './native-mcp-reload.mjs';
import {NativeMcpAuth} from './native-mcp-auth.mjs';
import {NativePlugins} from './native-plugins.mjs';
import {describeNativeCapabilities,nativeVersion,supportsNativeMethod,observedRuntimeSettings} from './native-capabilities.mjs';
import {catalogName,sessionIdentity} from './session-catalog.mjs';

const fail=(message,status=400)=>Object.assign(Error(message),{status});
const unavailable=error=>({state:error.rpc?.code===-32601||error.rpc?.code===-32602?'unsupported':'unavailable',reason:error.rpc?.code===-32601||error.rpc?.code===-32602?'The connected runtime does not support this request.':'Native inspection failed. Retry this view when the environment is available.'});
const pick=(source,keys)=>Object.fromEntries(keys.filter(k=>source?.[k]!==undefined).map(k=>[k,source[k]]));
const safeId=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,150}$/.test(value);
const sections=new Set(['settings','skills','mcp','apps','plugins','hooks','agents']);
const mutationFor={settings:'config/value/write',skills:'skills/config/write',mcp:'config/value/write',apps:'config/value/write'};
const methodFor={settings:'config/read',skills:'skills/list',mcp:'mcpServerStatus/list',apps:'app/installed',plugins:'plugin/installed',hooks:'hooks/list',agents:'thread/list'};

// User-opened native catalogs. Catalog RPCs may refresh native caches; never use writes as probes.
export class NativeConfiguration {
 constructor({codex,db,admissions,savedSettings=()=>null,requestedPermissions=()=>null,accountEvidence=()=>null,host=''}={}){Object.assign(this,{codex,savedSettings,requestedPermissions,host});if(db)this.mcpAuth=new NativeMcpAuth({db,codex,admissions,accountEvidence,context:id=>this.context(id)});if(db)this.mcpReload=new NativeMcpReload({db,codex,admissions,source:this.mcpAuth});if(db)this.plugins=new NativePlugins({db,codex,admissions,accountEvidence,context:id=>this.context(id)});
  this.appPages=new Map();this.accountEvidence=accountEvidence;this.configurationEpoch=0;this.configurationBoundaryAt=Date.now();
  const boundary=()=>{this.appPages.clear();this.configurationEpoch++;this.configurationBoundaryAt=Date.now();};
  codex.on?.('connected',boundary);codex.on?.('disconnected',boundary);
  codex.on?.('event',event=>{if(['account/updated','account/login/completed'].includes(event.method))boundary();});
 }
 configurationSource(){return {epoch:this.configurationEpoch,transport:this.codex.transport,accountId:this.accountEvidence(this.configurationBoundaryAt)?.accountId};}
 checkConfigurationSource(source){const now=this.configurationSource();if(!this.codex.ready||typeof source.accountId!=='string'||!source.accountId.trim()||source.accountId!==now.accountId||source.epoch!==now.epoch||source.transport!==now.transport)throw fail('Native account or connection changed or is unavailable. Reload configuration before changing it.',409);}

 async context(threadId,configurationSource=null){
  await this.codex.connect();if(configurationSource)this.checkConfigurationSource(configurationSource);
  if(!threadId)return null;
  const {thread}=await this.codex.call('thread/read',{threadId,includeTurns:false});if(configurationSource)this.checkConfigurationSource(configurationSource);
  if(thread?.id!==threadId)throw fail('Native session identity could not be verified.',409);
  return thread;
 }
 descriptor(threadId){return {...describeNativeCapabilities({version:nativeVersion(this.codex),userAgent:this.codex.initializeResult?.userAgent,connected:this.codex.ready!==false}),host:this.host,threadId:threadId||null,requested:{turnSettings:threadId?this.savedSettings(threadId):null,permissions:threadId?this.requestedPermissions(threadId):null},observed:observedRuntimeSettings(this.codex,threadId)};}
 async apps({threadId,appsView='installed',cursor,refresh,appId},source=null){
  if(!['installed','browse'].includes(appsView)||refresh!==undefined&&refresh!=='1'||cursor!==undefined&&(typeof cursor!=='string'||!cursor||cursor.length>128)||cursor&&refresh)throw fail('Choose an app view or refresh its first page.');
  await this.codex.connect();
  const browseAvailable=supportsNativeMethod(this.codex,'app/list');
  if((appsView==='browse'||refresh)&&!browseAvailable)throw fail('This runtime supports installed app inspection only. Discovery and refresh are unavailable.',409);
  if(!supportsNativeMethod(this.codex,'app/installed'))return {...this.descriptor(threadId),section:'apps',state:'unavailable',items:[],reason:'Installed app inspection is unavailable on this runtime.'};
  source??=this.configurationSource();this.checkConfigurationSource(source);
  const check=()=>this.checkConfigurationSource(source);
  const read=async(method,params)=>{check();const result=await this.codex.call(method,params);check();return result;};
  for(const [key,page]of this.appPages)if(page.expires<=Date.now())this.appPages.delete(key);
  const saved=cursor?this.appPages.get(cursor):null;
  if(cursor&&(!saved||saved.threadId!==threadId||saved.appsView!==appsView||saved.source.epoch!==source.epoch||saved.source.transport!==source.transport||saved.source.accountId!==source.accountId))throw fail('App page belongs to an expired or different source. Reload this view.',409);
  await this.context(threadId,source);
  const canConfigure=supportsNativeMethod(this.codex,'config/value/write');
  const text=(value,max)=>typeof value==='string'?value.slice(0,max):null;
  const installedRow=a=>({id:a.id,name:text(a.runtimeName,200)||a.id,installed:true,enabled:typeof a.enabled==='boolean'?a.enabled:null,callable:typeof a.callable==='boolean'?a.callable:null,discovered:false,authStatus:'unverified',configurable:canConfigure&&safeId(a.id)});
  try{
   let installed=saved?.installed;
   if(!installed){const result=await read('app/installed',{...(threadId?{threadId}:{}),forceRefresh:refresh==='1'});if(!Array.isArray(result?.apps)||result.apps.length>10000||result.apps.some(a=>!a||typeof a.id!=='string'||!a.id||a.id.length>512))throw fail('Invalid native installed app snapshot.',502);installed=result.apps.map(installedRow);}
   let items,next=null,total;
   if(appsView==='installed'){
    const offset=saved?.offset||0,rows=appId&&source?installed.filter(a=>a.id===appId):installed;items=rows.slice(offset,offset+30);total=rows.length;
    if(offset+30<rows.length)next={offset:offset+30};
   }else{
    const result=await read('app/list',{...(threadId?{threadId}:{}),limit:30,forceRefetch:refresh==='1',...(saved?.nativeCursor?{cursor:saved.nativeCursor}:{})});
    if(!Array.isArray(result?.data)||result.data.length>30||result.data.some(a=>!a||typeof a.id!=='string'||!a.id||a.id.length>512)||result.nextCursor!=null&&(typeof result.nextCursor!=='string'||!result.nextCursor||result.nextCursor.length>4096))throw fail('Invalid native app discovery page.',502);
    const byId=new Map(installed.map(a=>[a.id,a]));
    items=result.data.map(a=>{const known=byId.get(a.id);return {id:a.id,name:text(a.name,200)||a.id,description:text(a.description,4000),installed:!!known,enabled:known?known.enabled:null,configuredEnabled:typeof a.isEnabled==='boolean'?a.isEnabled:null,callable:known?known.callable:null,discovered:true,authStatus:'unverified',accessible:typeof a.isAccessible==='boolean'?a.isAccessible:null,configurable:!!known&&canConfigure&&safeId(a.id)};});
    if(result.nextCursor)next={nativeCursor:result.nextCursor};
   }
   check();let nextCursor=null;
   if(next){nextCursor=randomUUID();if(this.appPages.size>=32)this.appPages.delete(this.appPages.keys().next().value);this.appPages.set(nextCursor,{...next,source,threadId,appsView,installed,expires:Date.now()+5*60*1000});}
   return {...this.descriptor(threadId),section:'apps',state:'available',appsView,items,nextCursor,...(total===undefined?{}:{total}),browseAvailable,refreshAvailable:browseAvailable,readOnly:!canConfigure,notice:'Installed, enabled and callable are separate native observations. Discovery does not establish installation or authentication. Explicit refresh fetches connector sources and updates the native installed-tool snapshot; it does not sign in or execute tools.'+(!browseAvailable?' Read-only inspection on this Codex 0.163 runtime. Configuration changes remain unavailable.':'')};
  }catch(error){if(error.status===400||error.status===409)throw error;return {...this.descriptor(threadId),section:'apps',appsView,...unavailable(error),items:[],nextCursor:null,browseAvailable,refreshAvailable:browseAvailable,readOnly:!canConfigure};}
 }
 async inspect({threadId,section='settings',cursor,pluginView,appsView,appId,query,refresh}={},configurationSource=null){
  const call=async(method,params)=>{if(configurationSource)this.checkConfigurationSource(configurationSource);const result=await this.codex.call(method,params);if(configurationSource)this.checkConfigurationSource(configurationSource);return result;};
  if(!sections.has(section))throw fail('Unknown native settings section.');
  if(section==='apps')return this.apps({threadId,appsView,cursor,refresh,appId:configurationSource?appId:undefined},configurationSource);
  if(cursor!==undefined&&(typeof cursor!=='string'||cursor.length>4096))throw fail('Invalid catalog cursor.');
  let thread;try{let source;if(section==='plugins'&&this.plugins){await this.codex.connect();source=this.plugins.capture();}thread=await this.context(threadId,configurationSource);if(source)this.plugins.check(source);}catch(error){if(section!=='plugins'||!this.plugins||error.status===409)throw error;return {...this.descriptor(threadId),section,...unavailable(error),items:[],receipts:this.plugins.recent()};}
  const descriptor=this.descriptor(threadId);
  if(!supportsNativeMethod(this.codex,methodFor[section]))return {...descriptor,section,state:'unavailable',reason:descriptor.features.configuration.reason,items:[],...(section==='plugins'&&this.plugins?{receipts:this.plugins.recent()}:{})};
  const cwd=thread?.cwd,scope=cwd?{cwds:[cwd]}:{},canConfigure=!!mutationFor[section]&&supportsNativeMethod(this.codex,mutationFor[section]);
  try{
   let result,items=[],extra={};
   if(section==='settings'){
    result=await call('config/read',{includeLayers:true,...(cwd?{cwd}:{})});
    const userLayer=result.layers?.find(l=>l.name?.type==='user'&&!l.name.profile&&!l.disabledReason);
    extra={settings:pick(result.config,['model','model_provider','model_reasoning_effort','service_tier','sandbox_mode','approval_policy','web_search']),layers:(result.layers||[]).map(l=>({type:l.name?.type,version:l.version,disabled:!!l.disabledReason})),configVersion:userLayer?.version||null,notice:'Effective disk configuration, not proof of this running session’s permissions.'};
   }else if(section==='skills'){
    result=await call('skills/list',{...scope,forceReload:false});
    items=(result.data||[]).flatMap(group=>(group.skills||[]).map(s=>({...pick(s,['name','description','path','scope','enabled','pluginId']),id:s.path,configurable:canConfigure&&!!s.path})));
    extra={errorCount:(result.data||[]).reduce((n,g)=>n+(g.errors?.length||0),0)};
   }else if(section==='mcp'){
    const configuration=await call('config/read',{includeLayers:false,...(cwd?{cwd}:{})});
    result=await call('mcpServerStatus/list',{...(threadId?{threadId}:{}),detail:'toolsAndAuthOnly',limit:100,...(cursor?{cursor}:{})});
    items=(result.data||[]).map(s=>({id:s.name,name:s.name,enabled:configuration.config?.mcp_servers?.[s.name]?.enabled!==false,configurable:canConfigure&&safeId(s.name)&&Object.hasOwn(configuration.config?.mcp_servers||{},s.name),...pick(s,['authStatus','runtimeStatus','pluginId']),tools:Object.values(s.tools||{}).map(t=>pick(t,['name','description'])),toolCount:Object.keys(s.tools||{}).length,toolsUnavailable:!!s.toolsError}));
    if(!cursor)for(const [name,value]of Object.entries(configuration.config?.mcp_servers||{}))if(!items.some(i=>i.id===name))items.push({id:name,name,enabled:value.enabled!==false,configurable:canConfigure&&safeId(name),authStatus:'unknown',runtimeStatus:null,tools:[],toolCount:0});
    for(const item of items)item.canReviewAuthentication=!!this.mcpAuth&&supportsNativeMethod(this.codex,'mcpServer/oauth/login')&&item.enabled&&typeof configuration.config?.mcp_servers?.[item.id]?.url==='string'&&!configuration.config.mcp_servers[item.id].bearer_token_env_var;
    extra={canReviewReload:!!this.mcpReload&&supportsNativeMethod(this.codex,'config/mcpServer/reload'),nextCursor:result.nextCursor||null,notice:'Native connection and authorization status. Tool availability does not authorize execution. Enablement changes apply to subsequent native configuration loads; existing connections can retain their state.'};

   }else if(section==='plugins'){
    if(this.plugins){
     const browseAvailable=supportsNativeMethod(this.codex,'plugin/list'),page=await this.plugins.catalogPage({threadId,cwd,view:pluginView,query,cursor,refresh});
     return {...descriptor,section,state:'available',...page,items:page.items.map(item=>({...item,inspectable:browseAvailable&&item.inspectable===true})),browseAvailable,readOnly:!browseAvailable,receipts:this.plugins.recent(),notice:browseAvailable?'Catalog snapshot for browsing only. Review fresh details before changing a plugin; installed does not mean ready.':'Installed plugin state only. Read-only inspection on this Codex 0.163 runtime. Configuration changes remain unavailable.'};
    }
    result=await this.codex.call('plugin/installed',scope);
    items=(result.marketplaces||[]).flatMap(m=>(m.plugins||[]).filter(p=>p.installed).map(p=>({...pick(p,['id','name','enabled','installed','availability','disabledReason','version','localVersion']),marketplace:m.name})));
    extra={errorCount:result.marketplaceLoadErrors?.length||0,notice:'Installed plugin state only. Installation and removal remain in Codex; disabling a thread plugin is not yet enforced by this native version.'};
   }else if(section==='hooks'){
    result=await this.codex.call('hooks/list',scope);
    items=(result.data||[]).flatMap(g=>(g.hooks||[]).map(h=>({...pick(h,['key','eventName','enabled','handlerType','trustStatus','isManaged','pluginId']),id:h.key,name:h.eventName})));
    extra={errorCount:(result.data||[]).reduce((n,g)=>n+(g.errors?.length||0),0),notice:'Inspection only. Hook commands and prompts remain on the host; this view does not execute or approve hooks.'};
   }else if(section==='agents'){
    if(!thread)throw fail('Open a native session to inspect its agents.');
    const identity=sessionIdentity(thread),rootId=identity.parentThreadId||thread.id;
    result=await this.codex.call('thread/list',{ancestorThreadId:rootId,sourceKinds:['subAgent','subAgentReview','subAgentCompact','subAgentThreadSpawn','subAgentOther'],limit:100,useStateDbOnly:true,...(cursor?{cursor}:{})});
    items=(result.data||[]).map(t=>({id:t.id,threadId:t.id,name:catalogName(t),...sessionIdentity(t),status:t.status,model:t.model||null,reasoningEffort:t.reasoningEffort||null}));
    extra={parentThreadId:identity.parentThreadId,rootThreadId:rootId,nextCursor:result.nextCursor||null,notice:'Open an agent to read its work. Guidance follows the runtime’s direct-input capability; otherwise open its parent.'};
   }
   if(descriptor.runtime.compatibilityProfile)extra.notice=[extra.notice,'Read-only inspection on this Codex 0.163 runtime. Configuration changes remain unavailable.'].filter(Boolean).join(' ');
   return {...descriptor,section,state:'available',items,...extra,readOnly:!canConfigure};
  }catch(error){if(error.pluginCatalog||error.status===400||error.status===409)throw error;return {...descriptor,section,...unavailable(error),items:[],...(section==='plugins'&&this.plugins?{receipts:this.plugins.recent()}:{})};}
 }
 async configure({threadId,section,id,enabled,expectedVersion}={}){
  if(typeof enabled!=='boolean'||!['skills','apps','mcp'].includes(section))throw fail('Only explicit skill, app or MCP enablement changes are supported.');
  await this.codex.connect();
  if(!supportsNativeMethod(this.codex,mutationFor[section]))throw fail('Configuration changes are unavailable on this runtime; qualified catalogs remain read-only.',409);
  const source=this.configurationSource();this.checkConfigurationSource(source);
  const view=await this.inspect({threadId,section,appId:id},source);this.checkConfigurationSource(source);
  if(view.state!=='available')throw fail(view.reason||'Native configuration is unavailable.',409);
  const item=view.items.find(i=>i.id===id&&i.configurable);
  if(!item)throw fail('The selected item is no longer in this environment’s native catalog.',409);
  if(section==='skills'){
   if(!supportsNativeMethod(this.codex,'skills/config/write'))throw fail('Skill configuration is unavailable.',409);
   this.checkConfigurationSource(source);
   await this.codex.call('skills/config/write',{path:item.path,enabled});
  }else{
   if(typeof expectedVersion!=='string'||!expectedVersion)throw fail('Reload configuration before changing app enablement.',409);
   const settings=await this.inspect({threadId,section:'settings'},source);this.checkConfigurationSource(source);
   if(settings.configVersion!==expectedVersion)throw fail('Configuration changed. Reload before applying this change.',409);
   if(!supportsNativeMethod(this.codex,'config/value/write'))throw fail('Configuration changes are unavailable on this runtime; qualified catalogs remain read-only.',409);
   this.checkConfigurationSource(source);
   await this.codex.call('config/value/write',{keyPath:`${section==='apps'?'apps':'mcp_servers'}.${item.id}.enabled`,value:enabled,mergeStrategy:'replace',expectedVersion});
  }
  return {ok:true,section,id,requestedEnabled:enabled,notice:'Native configuration write acknowledged. Reload to inspect effective state; existing turns may retain their prior configuration.'};
 }
}
export function registerNativeConfigurationRoutes(app,{codex,route,requireId,...options}){
 const native=new NativeConfiguration({codex,...options});
 const threadId=req=>req.query.threadId?requireId(req.query.threadId):undefined;
 app.get('/api/native/capabilities',route(async(req,res)=>{await codex.connect();res.json(native.descriptor(threadId(req)));}));
 app.get('/api/native/configuration',route(async(req,res)=>{if(req.query.section==='apps')res.set('Cache-Control','no-store');if(req.query.section==='mcp'&&req.query.reloadReview==='1'){if(!native.mcpReload)throw fail('MCP reload unavailable.',409);const {_snapshot,...view}=await native.mcpReload.detail({threadId:threadId(req)},req.device.id);return res.set('Cache-Control','no-store').json(view);}res.json(await native.inspect({threadId:threadId(req),section:req.query.section||'settings',cursor:req.query.cursor,pluginView:req.query.pluginView,appsView:req.query.appsView,query:req.query.query,refresh:req.query.refresh}));}));
 app.get('/api/native/mcp-auth',route(async(req,res)=>{if(!native.mcpAuth)throw fail('MCP sign-in unavailable.',409);const {_snapshot,...view}=await native.mcpAuth.detail({threadId:threadId(req),name:req.query.name},req.device.id);res.set('Cache-Control','no-store').json(view);}));
 app.get('/api/native/plugins/receipts',route(async(req,res)=>res.json({receipts:native.plugins?.recent(typeof req.query.key==='string'?req.query.key:undefined)||[]})));
 app.get('/api/native/plugins/detail',route(async(req,res)=>{if(!native.plugins)throw fail('Plugin management is unavailable.',409);const {_origin,_snapshot,...view}=await native.plugins.detail({threadId:threadId(req),key:req.query.key});res.json(view);}));
 app.post('/api/native/configuration',route(async(req,res)=>{const body={...req.body,threadId:req.body.threadId?requireId(req.body.threadId):undefined};if(body.section==='mcp'&&['reload','reloadAcknowledge'].includes(body.action)){if(!native.mcpReload)throw fail('MCP reload unavailable.',409);return res.set('Cache-Control','no-store').json(await native.mcpReload.mutate(body,req.humanPrincipal));}if(body.section==='mcp'&&['oauthStart','oauthAcknowledge'].includes(body.action)){if(!native.mcpAuth)throw fail('MCP sign-in unavailable.',409);return res.set('Cache-Control','no-store').json(await native.mcpAuth[body.action==='oauthStart'?'start':'acknowledge'](body,req.humanPrincipal));}if(body.section==='plugins'){if(!native.plugins)throw fail('Plugin management is unavailable.',409);return res.json(await native.plugins.mutate(body,req.humanPrincipal));}res.json(await native.configure(body));}));
 return native;
}
