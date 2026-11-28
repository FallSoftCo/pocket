import {nativeVersion} from './native-capabilities.mjs';

const fail=(message,status=400)=>Object.assign(Error(message),{status});
const idPattern=/^[a-zA-Z0-9_-]{1,80}$/;
const modelPattern=/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}$/;
const safeError=()=>fail('Codex could not save the provider. Reload Providers to inspect its configuration before trying again.',502);

// Provider configuration belongs to Codex, including credentials. NextComp never
// keeps API keys in its database, preferences, operation receipts or responses.
export class ModelProviders {
 constructor(codex){this.codex=codex;this.writing=false;}
 async list(){
  await this.codex.connect();
  try{
   const result=await this.codex.call('modelProvider/list',{});
   return {state:'available',providers:result.data.map(p=>({id:p.id,name:p.name,baseUrl:p.baseUrl,canEdit:p.canEdit,kind:p.settings?.type,authMode:p.settings?.auth?.mode,envKey:p.settings?.auth?.envKey||null})),defaultProviderId:result.defaultProviderId,
    canConfigure:nativeVersion(this.codex)==='0.171.0',notice:'Provider selection applies to a new session. Existing sessions keep their provider. External providers use their own billing.'};
  }catch(error){if(error.rpc?.code===-32601||error.rpc?.code===-32600)return {state:'unavailable',providers:[],canConfigure:false,notice:'This Codex runtime does not expose provider management. Update Codex on this environment.'};throw error;}
 }
 async inspect(){
  const result=await this.list();if(!result.canConfigure)return result;
  const config=await this.codex.call('config/read',{includeLayers:true});
  const layer=config.layers?.find(l=>l.name?.type==='user'&&!l.name.profile&&!l.disabledReason);
  return {...result,configVersion:layer?.version||null,canConfigure:!!layer?.version};
 }
 async selection(input){
  const view=await this.list();
  if(view.state!=='available'){if(input)throw fail(view.notice,409);return null;}
  const providerId=input?.providerId||view.defaultProviderId;
  if(!view.providers.some(p=>p.id===providerId))throw fail('Choose a configured provider from this environment.');
  const model=input?.model?.trim()||null;
  if(model&&!modelPattern.test(model))throw fail('Enter a valid provider model ID.');
  if(providerId!=='openai'&&!model)throw fail('Choose a model for this provider in New task.');
  const effort=input?.effort||null;
  if(effort&&!['none','minimal','low','medium','high','xhigh','max','ultra'].includes(effort))throw fail('Invalid reasoning effort.');
  return {providerId,model,effort};
 }
 async save(input){
  if(this.writing)throw fail('Another provider configuration is being saved.',409);
  const {id,name,baseUrl,apiKey,envKey,expectedVersion}=input||{};
  if(!idPattern.test(id||'')||id==='openai')throw fail('Use a provider ID containing letters, numbers, underscores or hyphens.');
  if(typeof name!=='string'||!name.trim()||name.length>100)throw fail('Enter a provider name.');
  let url;try{url=new URL(baseUrl);}catch{throw fail('Enter the provider Responses API base URL.');}
  if(url.username||url.password||url.search||url.hash||!(url.protocol==='https:'||url.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(url.hostname)))throw fail('Use HTTPS, or HTTP for a local provider, without credentials or query parameters in the URL.');
  if(apiKey!=null&&(typeof apiKey!=='string'||apiKey.length>8192||/[\r\n]/.test(apiKey)))throw fail('Invalid API key.');
  if(envKey&&!/^[A-Z_][A-Z0-9_]{0,127}$/.test(envKey))throw fail('Enter an environment variable name, not its value.');
  if(apiKey&&envKey)throw fail('Choose an API key or a host environment variable.');
  if(typeof expectedVersion!=='string'||!expectedVersion)throw fail('Reload Providers before saving.',409);
  this.writing=true;
  try{
   const view=await this.inspect();
   if(!view.canConfigure)throw fail('Provider setup is unavailable on this Codex runtime.',409);
   if(view.configVersion!==expectedVersion)throw fail('Codex configuration changed. Reload Providers before saving.',409);
   const existing=view.providers.find(p=>p.id===id);
   if(existing&&!existing.canEdit)throw fail('This provider is managed outside the editable Codex configuration.',409);
   // Existing providers are not overwritten with a new URL/credential while
   // loaded sessions may still use them. A separately named provider is explicit.
   if(existing)throw fail('That provider ID already exists. Choose a new ID; existing sessions retain their configuration.',409);
   if(!apiKey&&!envKey)throw fail('Provide an API key or the name of an existing host credential variable.');
   const value={name:name.trim(),base_url:url.href.replace(/\/$/,''),wire_api:'responses',...(apiKey?{experimental_bearer_token:apiKey}:{env_key:envKey})};
   try{await this.codex.call('config/batchWrite',{expectedVersion,edits:[{keyPath:`model_providers.${id}`,value,mergeStrategy:'replace'}],reloadUserConfig:false});}catch{throw safeError();}
   const after=await this.list();
   if(!after.providers.some(p=>p.id===id))throw safeError();
   return {saved:true,providerId:id,notice:'Saved in Codex. Choose this provider and its model in New task. Credentials have not been tested with a paid request.'};
  }finally{this.writing=false;}
 }
}

export function registerModelProviderRoutes(app,{codex,route}){
 const providers=new ModelProviders(codex);
 app.get('/api/model-providers',route(async(_req,res)=>res.json(await providers.inspect())));
 app.post('/api/model-providers',route(async(req,res)=>res.json(await providers.save(req.body))));
 return providers;
}
