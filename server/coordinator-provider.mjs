const providerPattern=/^[a-zA-Z0-9_-]{1,80}$/;
const modelPattern=/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}$/;
const fail=(message,status=500)=>Object.assign(Error(message),{status});

// The coordinator is a hidden native session, so it must inherit this
// environment's configured provider rather than relying on a client picker.
// Credentials remain inside Codex and never enter this projection.
export async function coordinatorProvider(codex,{cwd=null}={}){
 await codex.connect();
 const result=await codex.call('config/read',{includeLayers:false,...(cwd?{cwd}:{})});
 const config=result?.config;
 if(!config||typeof config!=='object')throw fail('The coordinator provider configuration is unavailable.');
 const providerId=config.model_provider||'openai';
 if(!providerPattern.test(providerId))throw fail('The coordinator provider configuration is invalid.');
 if(providerId!=='openai'&&!Object.hasOwn(config.model_providers||{},providerId))throw fail(`The coordinator provider ${providerId} is not configured on this environment.`);
 const model=typeof config.model==='string'&&config.model.trim()?config.model.trim():null;
 if(model&&!modelPattern.test(model))throw fail('The coordinator model configuration is invalid.');
 if(providerId!=='openai'&&!model)throw fail('Choose a model for the coordinator provider on this environment.');
 return {providerId,...(model?{model}:{})};
}
