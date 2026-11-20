const el=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n;};
const field=(label,value='',type='text')=>{const wrapper=el('label',label),input=el('input');input.value=value;input.type=type;input.autocomplete=type==='password'?'new-password':'off';wrapper.append(input);return {wrapper,input};};

export class ProviderPanel{
 constructor({api,getScope}){Object.assign(this,{api,getScope});this.serial=0;this.dialog=el('dialog');this.dialog.className='native-settings';this.dialog.setAttribute('aria-label','Model providers');document.body.append(this.dialog);this.dialog.addEventListener('close',()=>{this.serial++;this.dialog.replaceChildren();});}
 close(){if(this.dialog.open)this.dialog.close();}
 async open(requestScope){this.close();this.origin=this.getScope();this.requestScope=requestScope??this.origin;this.dialog.showModal();await this.load();}
 async load(){
  const serial=++this.serial,origin=this.origin,current=()=>serial===this.serial&&this.dialog.open&&origin===this.getScope();
  const title=el('h2','Model providers'),status=el('p','Loading providers…'),body=el('div'),close=el('button','Done');status.setAttribute('role','status');body.className='provider-form';close.onclick=()=>this.close();
  this.dialog.replaceChildren(title,status,body,close);
  const reload=el('button','Reload providers');reload.onclick=()=>this.load();body.append(reload);
  try{
   const view=await this.api('/api/model-providers',undefined,{scope:this.requestScope});if(!current())return;
   status.textContent=view.notice;
   for(const p of view.providers||[])body.append(el('p',`${p.name} · ${p.id}${p.baseUrl?' · '+p.baseUrl:''}`));
   if(!view.canConfigure)return;
   const name=field('Name','DeepSeek'),id=field('Provider ID','deepseek'),url=field('Responses API base URL','https://api.deepseek.com','url'),key=field('API key','','password'),env=field('Or existing host credential variable');
   key.input.oninput=()=>{env.input.value='';};env.input.oninput=()=>{key.input.value='';};
   body.append(el('h3','Add provider'),name.wrapper,id.wrapper,url.wrapper,key.wrapper,env.wrapper,
    el('p','The key is stored in Codex’s private configuration on this environment. External providers use their own billing. Saving sends no model request.'));
   const save=el('button','Save provider');save.type='button';body.append(save);
   save.onclick=async()=>{
    if(!current()||save.disabled)return;
    save.disabled=true;reload.disabled=true;
    const request={id:id.input.value.trim(),name:name.input.value.trim(),baseUrl:url.input.value.trim(),apiKey:key.input.value,envKey:env.input.value.trim(),expectedVersion:view.configVersion};key.input.value='';
    try{const result=await this.api('/api/model-providers',request,{scope:this.requestScope});if(current()){await this.load();if(this.dialog.open)this.dialog.querySelector('[role=status]').textContent=result.notice;}}
    catch(error){if(current()){status.textContent=error.message+' Reload to inspect the original save before trying again.';reload.disabled=false;}}
    finally{delete request.apiKey;}
   };
  }catch(error){if(current())status.textContent=error.message;}
 }
}

export class NewTaskProvider{
 constructor({holder,api,manage}){
  Object.assign(this,{api});this.serial=0;
  const label=el('label','Provider');this.select=el('select');this.select.id='new-provider';label.append(this.select);
  this.model=field('Provider model ID');this.model.input.id='new-provider-model';this.model.wrapper.hidden=true;
  this.status=el('p');this.status.className='muted';const button=el('button','Manage providers');button.type='button';button.onclick=manage;
  holder.append(label,this.model.wrapper,this.status,button);this.select.onchange=()=>this.changed();
 }
 async load(){
  const serial=++this.serial;this.select.disabled=true;this.view=null;this.select.replaceChildren(el('option','Codex default'));this.model.wrapper.hidden=true;
  try{const view=await this.api('/api/model-providers');if(serial!==this.serial)return;this.view=view;this.status.textContent=view.notice;
   this.select.replaceChildren(...(view.providers||[]).map(p=>{const o=el('option',p.name);o.value=p.id;return o;}));this.select.value=view.defaultProviderId;this.select.disabled=view.state!=='available';this.changed();
  }catch(error){if(serial===this.serial)this.status.textContent=error.message;}
 }
 changed(){const provider=this.view?.providers?.find(p=>p.id===this.select.value);this.model.wrapper.hidden=!provider||provider.id==='openai';this.model.input.value=provider?.baseUrl?.startsWith('https://api.deepseek.com')?'deepseek-flash':'';}
 value(){const provider=this.view?.providers?.find(p=>p.id===this.select.value);if(!provider)return null;return {providerId:provider.id,...(provider.id!=='openai'?{model:this.model.input.value.trim(),...(provider.baseUrl?.startsWith('https://api.deepseek.com')?{effort:'high'}:{})}:{})};}
}
