// Resolve a provider/model before admitting a display-only batch. Credentials
// remain inside Codex; no API key or effective private config escapes this call.
export async function immersionProvider(codex, selection=null){
 await codex.connect();
 const {config}=await codex.call('config/read',{includeLayers:false});
 if(!config||typeof config!=='object')throw Error('Immersion provider configuration is unavailable.');
 const defaultId=config.model_provider||'openai',providerId=selection?.providerId||defaultId;
 if(providerId!=='openai'&&!Object.hasOwn(config.model_providers||{},providerId))throw Error('The selected immersion provider is not configured on this environment.');
 let model=selection?.model||null;
 if(!model&&providerId==='openai'){
  const list=await codex.call('model/list',{});
  model=list?.data?.find(m=>m.model==='gpt-6-luna')?.model||list?.data?.find(m=>m.model?.endsWith('-luna'))?.model||null;
 }
 model ||= providerId===defaultId?config.model:null;
 if(!model)throw Error('Choose a model for the immersion provider.');
 return {providerId,model,effort:selection?.effort||'medium'};
}
export function immersionSelection(value){
 if(value===null)return null;
 if(!value||typeof value!=='object'||! /^[a-zA-Z0-9_-]{1,80}$/.test(value.providerId||''))throw Object.assign(Error('Choose a configured immersion provider.'),{status:400});
 const model=value.model?.trim()||null;
 if(model&&!/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}$/.test(model))throw Object.assign(Error('Enter a valid immersion model ID.'),{status:400});
 if(value.providerId!=='openai'&&!model)throw Object.assign(Error('Enter the immersion model ID.'),{status:400});
 const effort=value.effort||'medium';
 if(!['none','minimal','low','medium','high','xhigh','max','ultra'].includes(effort))throw Object.assign(Error('Invalid immersion reasoning effort.'),{status:400});
 return {providerId:value.providerId,model,effort};
}
export async function immersionProviderChoices(codex){
 await codex.connect();const {config}=await codex.call('config/read',{includeLayers:false});
 if(!config)throw Error('Immersion provider configuration is unavailable.');
 const providers=[{id:'openai',name:'OpenAI',baseUrl:''}];
 for(const [id,p]of Object.entries(config.model_providers||{})){
  if(id==='openai')continue;
  let baseUrl='';try{const u=new URL(p.base_url);baseUrl=u.origin;}catch{}
  providers.push({id,name:typeof p.name==='string'?p.name:id,baseUrl});
 }
 return {state:'available',providers,defaultProviderId:config.model_provider||'openai',notice:'Immersion uses the selected provider. External providers use their own billing. Credentials stay in Codex.'};
}
