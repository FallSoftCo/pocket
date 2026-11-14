import test from 'node:test';
import assert from 'node:assert/strict';
import {NativeConfiguration} from '../server/native-configuration.mjs';
import {describeNativeCapabilities,supportsNativeMethod,rememberRuntimeSettings,observedRuntimeSettings,nativeVersion} from '../server/native-capabilities.mjs';
import {validateTurnSettings,turnOverrides} from '../server/turn-settings.mjs';
import {environmentRoute,qualifyPayload,scopedId} from '../server/web-environments.mjs';

function fixture(){
 const calls=[],state={version:'v1',skills:true,app:true,mcp:false,error:null};
 const codex={ready:true,initializeResult:{userAgent:'codex_cli_rs/0.167.0 (linux)'},async connect(){},async call(method,params){
  calls.push({method,params});if(state.error?.method===method)throw state.error.error;
  if(method==='thread/read')return {thread:{id:params.threadId,cwd:'/project',parentThreadId:params.threadId==='child-one'?'parent-one':null}};
  if(method==='config/read')return {config:{model:'fixture',sandbox_mode:'read-only',approval_policy:'on-request',mcp_servers:{native_tool:{enabled:state.mcp,env:{SECRET:'NEVER RETURN'},http_headers:{Authorization:'NEVER RETURN'}}},api_key:'NEVER RETURN'},layers:[{name:{type:'user',file:'/private/config.toml'},version:state.version,config:{api_key:'NEVER RETURN'}}]};
  if(method==='skills/list')return {data:[{skills:[{name:'fixture',description:'Skill',enabled:state.skills,path:'/skills/fixture/SKILL.md',scope:'user'}],errors:[]}]};
  if(method==='skills/config/write'){state.skills=params.enabled;return {};}
  if(method==='app/installed')return {apps:[{id:'app_fixture',runtimeName:'Fixture app',enabled:state.app,callable:false,secret:'NEVER RETURN'}]};
  if(method==='mcpServerStatus/list')return {data:[{name:'native_tool',authStatus:'notLoggedIn',tools:{a:{name:'safe_tool',description:'Description',inputSchema:{secret:'NEVER RETURN'}}}}],nextCursor:'page2'};
  if(method==='hooks/list')return {data:[{hooks:[{key:'hook-a',eventName:'sessionStart',handlerType:'command',command:'SECRET TOKEN',enabled:false,trustStatus:'untrusted',isManaged:false}],errors:[]}]};
  if(method==='plugin/installed')return {marketplaces:[{name:'market',plugins:[{id:'fixture@market',name:'Fixture',enabled:true,installed:true,shareContext:{secret:'NEVER RETURN'}}]}]};
  if(method==='thread/list')return {data:[{id:'child-one',parentThreadId:'parent-one',agentNickname:'Worker',source:{subAgent:{}},canAcceptDirectInput:false,status:{type:'idle'}}],nextCursor:'agents-page2'};
  if(method==='config/value/write'){assert.equal(params.expectedVersion,state.version);state.version='v2';return {status:'ok'};}
  throw Error('Unexpected RPC '+method);
 }};
 const native=new NativeConfiguration({codex,host:'fixture-host',savedSettings:()=>({model:'requested-model'}),requestedPermissions:()=>'full'});
 return {native,codex,calls,state};
}
test('capabilities identify connected runtime, never infer from a stored thread or requested permission',()=>{
 const f=fixture();assert.equal(nativeVersion(f.codex),'0.167.0');assert.equal(supportsNativeMethod(f.codex,'thread/fork'),true);
 assert.equal(supportsNativeMethod(f.codex,'thread/goal/set'),false);
 assert.equal(describeNativeCapabilities({version:'0.166.0'}).features.skills.state,'unavailable');
 assert.equal(describeNativeCapabilities({version:'0.167.0',connected:false}).features.skills.state,'unavailable');
 assert.equal(f.native.descriptor('parent-one').observed.state,'unavailable');
 rememberRuntimeSettings(f.codex,'thread/read',{thread:{id:'parent-one'},approvalPolicy:'never',sandbox:{type:'dangerFullAccess'}});
 assert.equal(observedRuntimeSettings(f.codex,'parent-one').state,'unavailable');
 rememberRuntimeSettings(f.codex,'thread/resume',{thread:{id:'parent-one'},approvalPolicy:'on-request',sandbox:{type:'readOnly'},accessToken:'NEVER RETURN'});
 const descriptor=f.native.descriptor('parent-one');assert.equal(descriptor.requested.permissions,'full');assert.equal(descriptor.observed.settings.sandbox.type,'readOnly');assert.ok(!JSON.stringify(descriptor).includes('NEVER RETURN'));
});
test('explicit catalog reads have no mutation, resume, goal, refresh or model operations and redact configuration secrets',async()=>{
 const f=fixture();
 for(const section of ['settings','skills','mcp','apps','plugins','hooks','agents']){
  const view=await f.native.inspect({threadId:'child-one',section});assert.equal(view.state,'available',section);assert.ok(!JSON.stringify(view).includes('NEVER RETURN'),section);assert.ok(!JSON.stringify(view).includes('SECRET TOKEN'),section);
 }
 assert.ok(f.calls.every(c=>!/write|resume|start|turn\/|goal|reconcile|reload/.test(c.method)));
 assert.equal(f.calls.find(c=>c.method==='thread/list').params.ancestorThreadId,'parent-one');
 assert.equal(f.calls.find(c=>c.method==='app/installed').params.forceRefresh,false);
 assert.equal(f.calls.find(c=>c.method==='skills/list').params.forceReload,false);
});
test('unqualified and method-rejected runtimes show unavailable or unsupported without write probes',async()=>{
 const f=fixture();f.codex.initializeResult.userAgent='codex/0.168.0';assert.equal((await f.native.inspect({section:'skills'})).state,'unavailable');assert.equal(f.calls.length,0);
 f.codex.initializeResult.userAgent='codex/0.167.0';f.state.error={method:'hooks/list',error:{rpc:{code:-32601},message:'SECRET TOKEN'}};
 const view=await f.native.inspect({section:'hooks'});assert.equal(view.state,'unsupported');assert.ok(!view.reason.includes('SECRET'));
});
test('skill mutation validates exact live catalog identity and only writes the explicit boolean',async()=>{
 const f=fixture();await assert.rejects(f.native.configure({section:'skills',id:'/arbitrary/path',enabled:true}),/no longer/);
 await assert.rejects(f.native.configure({section:'skills',id:'/skills/fixture/SKILL.md',enabled:'true'}),/Only explicit/);
 const result=await f.native.configure({threadId:'parent-one',section:'skills',id:'/skills/fixture/SKILL.md',enabled:false});assert.equal(result.ok,true);
 assert.deepEqual(f.calls.filter(c=>c.method.endsWith('/write')),[{method:'skills/config/write',params:{path:'/skills/fixture/SKILL.md',enabled:false}}]);
 assert.equal((await f.native.inspect({section:'skills'})).items[0].enabled,false);
});
test('app and MCP writes require a matching configuration version; never accept arbitrary key paths',async()=>{
 const f=fixture();
 await assert.rejects(f.native.configure({section:'apps',id:'app_fixture',enabled:false,expectedVersion:'old'}),/Configuration changed/);
 await assert.rejects(f.native.configure({section:'apps',id:'app_fixture',enabled:false}),/Reload configuration/);
 await f.native.configure({section:'apps',id:'app_fixture',enabled:false,expectedVersion:'v1',keyPath:'api_key'});
 assert.deepEqual(f.calls.find(c=>c.method==='config/value/write').params,{keyPath:'apps.app_fixture.enabled',value:false,mergeStrategy:'replace',expectedVersion:'v1'});
 await f.native.configure({section:'mcp',id:'native_tool',enabled:true,expectedVersion:'v2'});
 assert.equal(f.calls.filter(c=>c.method==='config/value/write').at(-1).params.keyPath,'mcp_servers.native_tool.enabled');
 assert.ok(!f.calls.some(c=>c.method==='config/mcpServer/reload'));
});
test('uncertain writes are submitted once; unsupported plugin and hook changes never dispatch',async()=>{
 const f=fixture();f.state.error={method:'skills/config/write',error:Error('Native connection closed')};
 await assert.rejects(f.native.configure({section:'skills',id:'/skills/fixture/SKILL.md',enabled:false}),/connection closed/);
 assert.equal(f.calls.filter(c=>c.method==='skills/config/write').length,1);
 for(const section of ['plugins','hooks','goals'])await assert.rejects(f.native.configure({section,id:'x',enabled:true}),/Only explicit/);
});
test('native agents retain parent routing and bounded cursor inspection',async()=>{
 const f=fixture(),view=await f.native.inspect({threadId:'parent-one',section:'agents',cursor:'page-one'});
 assert.equal(view.items[0].canAcceptDirectInput,false);assert.equal(view.items[0].threadId,'child-one');assert.equal(view.nextCursor,'agents-page2');
 assert.equal(f.calls.at(-1).params.limit,100);assert.equal(f.calls.at(-1).params.cursor,'page-one');
});
test('model service tiers are actual catalog choices and only apply on new turns',()=>{
 const models=[{model:'test',defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'medium'}],serviceTiers:[{id:'priority',name:'Priority'}]}];
 assert.throws(()=>validateTurnSettings({model:'test',mode:'default',serviceTier:'invented'},models),/advertised/);
 const settings=validateTurnSettings({model:'test',mode:'plan',serviceTier:'priority'},models);assert.equal(turnOverrides(settings).serviceTier,'priority');
 assert.equal(turnOverrides({...settings,serviceTier:null}).serviceTier,undefined);
});
test('remote native settings queries and agent navigation preserve host identity',()=>{
 const child=scopedId('mac','child-one');
 assert.equal(environmentRoute('/api/environments/mac/native/configuration?section=skills&threadId='+child).path,'/api/native/configuration?section=skills&threadId=child-one');
 assert.throws(()=>environmentRoute('/api/environments/phone/native/configuration?threadId='+child),/different environment/);
 const result=qualifyPayload({rootThreadId:'parent-one',items:[{threadId:'child-one',parentThreadId:'parent-one',id:'child-one'}]},'mac');
 assert.equal(result.items[0].threadId,child);assert.equal(result.rootThreadId,scopedId('mac','parent-one'));
});

const macUserAgent='codex_pocket/0.163.0 (Mac OS 26.6.0; arm64) unknown (nextcomp_compatibility_readonly; 1)';
test('Mac 0.163 qualification grants only observed catalog methods, not platform-wide or write compatibility',()=>{
 const f=fixture();f.codex.initializeResult.userAgent=macUserAgent;
 for(const method of ['config/read','skills/list','mcpServerStatus/list','app/installed','plugin/installed','hooks/list','thread/list','thread/read'])assert.equal(supportsNativeMethod(f.codex,method),true,method);
 for(const method of ['config/value/write','skills/config/write','config/mcpServer/reload','thread/settings/update','thread/fork','thread/revert','thread/compact/start','review/start','thread/backgroundTerminals/list','thread/backgroundTerminals/terminate','thread/items/read','thread/goal/set'])assert.equal(supportsNativeMethod(f.codex,method),false,method);
 const descriptor=f.native.descriptor('parent-one');
 assert.equal(descriptor.runtime.compatibilityProfile,'mac-arm64-0.163-catalog-reads-20261107');
 for(const key of ['configuration','skills','mcp','apps']){
  assert.equal(descriptor.features[key].state,'available');assert.equal(descriptor.features[key].readOnly,true);assert.equal(descriptor.features[key].mutation.state,'unavailable');
 }
 assert.equal(descriptor.features.backgroundTerminals.state,'unavailable');assert.match(descriptor.features.backgroundTerminals.reason,/read-only client presentation/);
 assert.equal(descriptor.features.nativeRequests.state,'unavailable');
 assert.equal(describeNativeCapabilities({version:'0.163.0',userAgent:macUserAgent,connected:false}).features.skills.state,'unavailable');
 for(const userAgent of ['codex_cli_rs/0.163.0 (linux)','codex_pocket/0.163.0 (Mac OS 26.6.0; x86_64)','codex_pocket/0.164.0 (Mac OS 26.6.0; arm64)','codex/0.163.0']){
  f.codex.initializeResult.userAgent=userAgent;assert.equal(supportsNativeMethod(f.codex,'skills/list'),false,userAgent);
 }
 assert.equal(describeNativeCapabilities({version:'0.163.0'}).features.skills.state,'unavailable');
});
test('Mac catalogs retain redaction and scope, and reject stale or forged mutations before any native dispatch',async()=>{
 const f=fixture();f.codex.initializeResult.userAgent=macUserAgent;
 for(const section of ['settings','skills','mcp','apps','plugins','hooks','agents']){
  const view=await f.native.inspect({threadId:'child-one',section});
  assert.equal(view.state,'available',section);assert.equal(view.readOnly,true,section);assert.match(view.notice,/Configuration changes remain unavailable/);
  assert.ok(view.items.every(i=>i.configurable!==true),section);assert.ok(!/NEVER RETURN|SECRET TOKEN/.test(JSON.stringify(view)),section);
 }
 assert.equal(f.calls.find(c=>c.method==='thread/list').params.ancestorThreadId,'parent-one');
 const count=f.calls.length;
 for(const [section,id] of [['skills','/skills/fixture/SKILL.md'],['apps','app_fixture'],['mcp','native_tool']])for(const expectedVersion of [undefined,'old','v1']){
  await assert.rejects(f.native.configure({threadId:'child-one',section,id,enabled:false,expectedVersion}),error=>error.status===409&&/remain read-only/.test(error.message));
 }
 assert.equal(f.calls.length,count,'rejected changes must not even refresh catalogs or inspect a replacement thread');
 assert.ok(f.calls.every(c=>!/write|resume|start|turn\/|goal|reconcile|reload/.test(c.method)));
});

test('a runtime changed during the settings read cannot inherit the previous connection’s write capability',async()=>{
 const f=fixture(),call=f.codex.call.bind(f.codex);
 f.codex.call=async(method,params)=>{const result=await call(method,params);if(method==='config/read'&&params.includeLayers)f.codex.initializeResult.userAgent=macUserAgent;return result;};
 await assert.rejects(f.native.configure({section:'apps',id:'app_fixture',enabled:false,expectedVersion:'v1'}),error=>error.status===409&&/remain read-only/.test(error.message));
 assert.ok(!f.calls.some(c=>c.method==='config/value/write'));
});
