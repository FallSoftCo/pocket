// Synthetic plugin service only; validated against retained installed0.167 schemas.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {AjvJsonSchemaValidator} from '@modelcontextprotocol/sdk/validation/ajv-provider.js';
const contract=JSON.parse(await readFile(new URL('../fixtures/native-contract-0167/plugins.json',import.meta.url)));
const require=createRequire(import.meta.resolve('@modelcontextprotocol/sdk/validation/ajv-provider.js')),Ajv=require('ajv').default,ajv=new Ajv({strict:false});
for(const format of ['int32','int64','uint32','uint64','uint','float','double'])ajv.addFormat(format,{type:'number',validate:Number.isFinite});
const validator=new AjvJsonSchemaValidator(ajv),checks=new Map();
function check(name,value){let f=checks.get(name);if(!f){f=validator.getValidator({...contract.schemas[name],definitions:contract.definitions});checks.set(name,f);}const result=f(value);assert.equal(result.valid,true,name+': '+result.errorMessage);return value;}
export function pluginState(){return {installed:false,authPolicy:'ON_INSTALL',installPolicy:'AVAILABLE',availability:'AVAILABLE',interstitial:true,remoteId:'plugins_fixture',version:'v1',catalogSize:1,installedBulk:0};}
export async function pluginReply(state,method,params){
 const names={'plugin/list':'PluginList','plugin/installed':'PluginInstalled','plugin/read':'PluginRead','plugin/install':'PluginInstall','plugin/uninstall':'PluginUninstall'},name=names[method];if(!name)return null;
 check(name+'Params',params);
 const summary=()=>({id:'fixture@market',name:'fixture',source:state.local?{type:'local',path:'/synthetic/plugin'}:{type:'remote'},remotePluginId:state.local?null:state.remoteId,version:state.version,localVersion:state.installed?state.version:null,shareContext:null,installed:state.installed,enabled:state.installed,installPolicy:state.installPolicy,installPolicySource:null,mustShowInstallationInterstitial:state.interstitial,authPolicy:state.authPolicy,availability:state.availability,disabledReason:null,eligiblePlanTypes:null,interface:{displayName:'Fixture plugin',shortDescription:'Synthetic plugin',longDescription:null,developerName:'Fixture',category:null,capabilities:['Read synthetic tasks'],websiteUrl:null,privacyPolicyUrl:'https://fixture.invalid/privacy',termsOfServiceUrl:null,defaultPrompt:null,brandColor:null,composerIcon:null,composerIconUrl:null,logo:null,logoDark:null,logoUrl:null,logoDarkUrl:null,screenshots:[],screenshotUrls:[]},keywords:[]});
 const count=state.catalogSize||1;
 const at=index=>{const base=summary();if(index===count-1)return base;const number=String(index).padStart(5,'0'),installed=index<(state.installedBulk||0);return {...base,id:`catalog_${number}@market`,name:`Catalog plugin ${number}`,remotePluginId:`plugins_bulk_${number}`,installed,enabled:installed,localVersion:installed?state.version:null,source:{type:'remote'},interface:{...base.interface,displayName:`Catalog plugin ${number}`}};};
 let result;
 if(method==='plugin/list'||method==='plugin/installed')result={marketplaces:[{name:'market',path:state.local?'/synthetic/marketplace.json':null,interface:null,plugins:state.missing||method==='plugin/list'&&state.installedOnly?[]:Array.from({length:count},(_,i)=>at(i)).filter(p=>method!=='plugin/installed'||p.installed)}],marketplaceLoadErrors:[],...(method==='plugin/list'?{featuredPluginIds:[]}:{})};
 if(method==='plugin/read'){
  const bulk=/^plugins_bulk_(\d{5})$/.exec(params.pluginName);const selected=bulk?Number(bulk[1]):count-1;assert.ok(selected>=0&&selected<count);if(!bulk)assert.equal(params.pluginName,state.local?'fixture':state.remoteId);if(state.local){assert.equal(params.marketplacePath,'/synthetic/marketplace.json');assert.equal(params.remoteMarketplaceName,undefined);}else assert.equal(params.remoteMarketplaceName,'market');
  result={plugin:{marketplaceName:'market',marketplacePath:state.local?'/synthetic/marketplace.json':null,summary:at(selected),shareUrl:null,description:'Synthetic capability details '+state.version,skills:[],onboardingSkill:null,hooks:[],apps:[],appTemplates:[],mcpServers:['fixture-mcp'],scheduledTasks:null}};
  if(state.wrongIdentity)result.plugin.marketplaceName='other';
  // Capture before the barrier: releasing after another install must still
  // deliver the old snapshot, not a reconstruction from mutated fixture state.
  if(state.holdRead){const hold=state.holdRead;state.holdRead=null;hold.started();await hold.wait;}
 }
 if(method==='plugin/install'){
  assert.equal(params.pluginName,state.local?'fixture':state.remoteId);if(state.local){assert.equal(params.marketplacePath,'/synthetic/marketplace.json');assert.equal(params.remoteMarketplaceName,undefined);}else assert.equal(params.remoteMarketplaceName,'market');assert.ok(params.installAttemptId);state.installed=true;
  if(state.dropInstall){state.dropInstall=false;return {drop:true};}
  if(state.holdInstall){const hold=state.holdInstall;state.holdInstall=null;hold.started();await hold.wait;}
  if(state.failInstall){state.failInstall=false;return {error:{code:-32603,message:'PRIVATE provider failure after installation'}};}
  result={authPolicy:state.authPolicy,appsNeedingAuth:[{id:'app-fixture',name:'Fixture app',description:'Fixture',logoUrl:null,logoUrlDark:null,distributionChannel:null,installUrl:'https://fixture.invalid/install',branding:null,appMetadata:null,labels:null,isAccessible:false,isEnabled:true}]};
 }
 if(method==='plugin/uninstall'){assert.equal(params.pluginId,state.local?'fixture@market':state.remoteId);state.installed=false;result={};}
 return {result:check(name+'Response',result)};
}

export function pluginAccountReply(params,accountId='synthetic-account'){
 check('GetAccountRateLimitsParams',params);
 return check('GetAccountRateLimitsResponse',{accountId,ordinaryUsageAllowed:true,rateLimitUpsell:null,rateLimitResetCredits:null,rateLimitsByLimitId:null,rateLimits:{limitId:'codex',limitName:null,normalModelSlug:null,primary:{usedPercent:20,windowDurationMins:10080,resetsAt:2000000000},secondary:null,credits:null,individualLimit:null,spendControlReached:false,planType:'pro',rateLimitReachedType:null}});
}
