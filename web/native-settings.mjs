import {mcpReloadButton} from './native-mcp-reload.mjs';
import {mcpAuthenticationButton} from './native-mcp-auth.mjs';
import {pluginBrowserControls} from './native-plugin-browser.mjs';
import {pluginDetailsButton,operationLabel} from './native-plugins.mjs';
const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
const sections=['settings','agents','skills','mcp','apps','plugins','hooks'];
const titles={settings:'Runtime & permissions',agents:'Agents',skills:'Skills',mcp:'MCP servers',apps:'Apps',plugins:'Plugins',hooks:'Hooks'};
const display=value=>typeof value==='string'?value:JSON.stringify(value);
export function addServiceTierControl({holder,modelSelect,getModels,getStored}){
 const label=node('label','Service tier'),select=node('select');select.id='service-tier';label.append(select);holder.append(label);
 return {refresh(){const model=getModels().find(m=>m.model===modelSelect.value),stored=getStored();select.replaceChildren();const inherit=node('option','Inherit native setting');inherit.value='';select.append(inherit);for(const tier of model?.serviceTiers||[]){const option=node('option',tier.name||tier.id);option.value=tier.id;select.append(option);}select.value=stored?.model===model?.model?stored?.serviceTier||'':'';select.disabled=!model?.serviceTiers?.length;},value:()=>select.value||null};
}
export class NativeSettingsPanel {
 constructor({api,getScope,openThread}){
  Object.assign(this,{api,getScope,openThread});this.serial=0;
  this.dialog=node('dialog');this.dialog.className='native-settings';this.dialog.setAttribute('aria-label','Codex settings and agents');
  this.title=node('h2','Codex settings & agents');this.source=node('p');this.tabs=node('nav');this.tabs.setAttribute('aria-label','Settings sections');this.body=node('section');this.feedback=node('p');this.feedback.setAttribute('role','status');this.pluginFooter=node('footer');this.pluginFooter.dataset.pluginPager='';
  this.scroll=node('div');this.scroll.className='native-settings-scroll';this.scroll.append(this.title,this.source,this.tabs,this.feedback,this.body);
  const close=node('button','Close');close.onclick=()=>this.dialog.close();this.dialog.append(this.scroll,this.pluginFooter,close);document.body.append(this.dialog);
  for(const section of sections){const b=node('button',titles[section]);b.onclick=()=>this.load(section);this.tabs.append(b);}
 }
 event(m){if(m.type==='nativeAuthBoundary'||m.type==='status'&&m.connected===false||m.type==='environmentStatus'&&(m.available??m.connected)===false)this.closeForNavigation();}
 closeForNavigation(){this.serial++;this.origin=null;if(this.dialog.open)this.dialog.close();}
 async open(){if(this.dialog.open)this.closeForNavigation();this.pluginView='installed';this.pluginQuery='';this.appsView='installed';this.scope=this.getScope();this.threadId=this.scope==='coordinator'?null:this.scope;this.origin=Object.freeze({scope:this.scope,threadId:this.threadId});this.dialog.showModal();await this.load('settings');}
 // Object identity distinguishes close/reopen even in the same environment.
 // Captured operations never acquire the destination of a later panel opening.
 current(origin=this.origin){return !!origin&&origin===this.origin&&this.getScope()===origin.scope&&this.dialog.open;}
 async request(section,cursor,origin=this.origin,plugin={}){return this.api('/api/native/configuration?section='+section+(origin.threadId?'&threadId='+encodeURIComponent(origin.threadId):'')+(cursor?'&cursor='+encodeURIComponent(cursor):'')+(section==='plugins'?'&pluginView='+encodeURIComponent(plugin.view||'installed')+'&query='+encodeURIComponent(plugin.query||'')+(plugin.refresh?'&refresh=1':''):section==='apps'?'&appsView='+encodeURIComponent(plugin.view||'installed')+(plugin.refresh?'&refresh=1':''):''),undefined,{scope:origin.scope});}
 async load(section,cursor,plugin={}){
  if(section==='apps'){this.appsView=plugin.view||this.appsView||'installed';plugin={...plugin,view:this.appsView};}
  if(section==='plugins'){this.pluginView=plugin.view||this.pluginView||'installed';this.pluginQuery=plugin.query??this.pluginQuery??'';plugin={...plugin,view:this.pluginView,query:this.pluginQuery};}
  const serial=++this.serial,origin=this.origin;this.feedback.textContent='Loading…';this.section=section;
  this.dialog.classList.toggle('plugin-catalog',['plugins','apps'].includes(section));this.pluginFooter.replaceChildren();
  if(['plugins','apps'].includes(section)){this.scroll.scrollTop=0;this.body.scrollTop=0;this.dialog.scrollTop=0;}
  if(!cursor||['plugins','apps'].includes(section))this.body.replaceChildren();
  try{
   const view=await this.request(section,cursor,origin,plugin);if(serial!==this.serial||!this.current(origin))return;
   if(['plugins','apps'].includes(section)&&(view.items?.length||0)>30)throw Error('Native catalog page exceeds this client’s limit. Update the backend and reload.');
   this.source.textContent=`${view.host||'Selected environment'} · Codex ${view.runtime?.version||'version unavailable'}`;
   this.feedback.textContent=view.state==='available'?(view.notice||''):view.reason||'Unavailable';
   if(section==='settings')this.renderSettings(view);else this.renderItems(view,!['plugins','apps'].includes(section)&&!!cursor);
   if(section==='plugins'&&cursor){const first=this.body.querySelector('[data-plugin-row]');if(first)this.scroll.scrollTop+=first.getBoundingClientRect().top-this.scroll.getBoundingClientRect().top;}
  }catch(error){if(serial===this.serial&&this.current(origin)){
   this.feedback.textContent=error.message;
   if(section==='apps'){const retry=node('button','Reload apps view');retry.onclick=()=>{if(this.current(origin)&&this.serial===serial)return this.load('apps',undefined,{view:plugin.view});};this.body.replaceChildren(retry);}
   if(section==='plugins'){const retry=node('button','Reload Plugins');retry.onclick=()=>{if(this.current(origin)&&this.serial===serial)return this.load('plugins',undefined,{...plugin,refresh:true});};this.body.replaceChildren(retry);}
  }}
 }
 renderSettings(view){
  const requested=node('p','Requested permissions: '+(view.requested?.permissions||'No saved preference for this session'));
  const observed=node('p',view.observed?.state==='observed'?'Last observed native permissions ('+view.observed.observedAt+'): '+display(view.observed.settings?.sandbox)+' · approvals '+display(view.observed.settings?.approvalPolicy):view.observed?.reason||'Runtime permissions unavailable');
  this.body.append(requested,observed);
  if(view.observed?.notice)this.body.append(node('p',view.observed.notice));
  for(const [key,value]of Object.entries(view.settings||{}))this.body.append(node('p',key.replaceAll('_',' ')+': '+display(value)));
  const details=node('details'),summary=node('summary','Native capability support');details.append(summary);
  for(const [key,value]of Object.entries(view.features||{}))details.append(node('p',`${key}: ${value.state} · ${value.reason}`));this.body.append(details);
  const refresh=node('button','Reload settings');refresh.onclick=()=>this.load('settings');this.body.append(refresh);
 }
 renderItems(view,append){
  if(!append)this.body.replaceChildren();
  this.body.querySelector('[data-more]')?.remove();
  if(view.parentThreadId||view.rootThreadId){const id=view.parentThreadId||view.rootThreadId;if(id!==this.threadId){const parent=node('button','Open parent task');parent.onclick=()=>this.navigate(id);this.body.append(parent);}}
  if(view.section==='mcp'&&!append&&view.canReviewReload)this.body.append(mcpReloadButton(this));
  if(view.section==='plugins')this.body.append(pluginBrowserControls(this,view));
  if(view.section==='apps'){
   const origin=this.origin,serial=this.serial,load=(mode,cursor,refresh=false)=>{if(this.current(origin)&&this.serial===serial)return this.load('apps',cursor,{view:mode,refresh});};
   const controls=node('nav');controls.setAttribute('aria-label','Apps catalog');
   const installed=node('button','Installed apps');installed.onclick=()=>load('installed');controls.append(installed);
   if(view.browseAvailable){const browse=node('button','Browse apps');browse.onclick=()=>load('browse');controls.append(browse);}
   this.body.append(controls,node('p',view.appsView==='browse'?'Available apps · discovery is not installation':'Installed apps'));
   if(view.nextCursor){const next=node('button','Next apps page');next.onclick=()=>load(view.appsView,view.nextCursor);this.pluginFooter.append(next);}
   if(view.refreshAvailable){const refresh=node('button','Refresh apps');refresh.onclick=()=>load(view.appsView,undefined,true);this.pluginFooter.append(refresh);}
  }
  if(view.section==='plugins')for(const receipt of view.receipts||[]){const row=node('article');row.append(node('p',`${receipt.name} · ${receipt.action} · ${operationLabel(receipt.state)}: ${receipt.notice}`),pluginDetailsButton(this,{key:receipt.key},'Review operation'));this.body.append(row);}
  if(!view.items?.length)this.body.append(node('p',view.state==='available'?'No items reported by this native catalog.':'Catalog unavailable.'));
  for(const item of view.items||[]){
   const row=node('article');if(view.section==='plugins')row.dataset.pluginRow='';if(view.section==='apps')row.dataset.appRow='';row.append(node('h3',item.name||item.id));
   if(view.section==='apps'){
    const state=value=>value===true?'Yes':value===false?'No':'Unknown';
    row.append(node('p',`Installed: ${state(item.installed)} · Enabled: ${state(item.enabled)} · Callable: ${state(item.callable)}`));
    const details=node('details');details.append(node('summary','App details'),node('p',item.description||'No description reported.'),node('p','Authentication: '+(item.authStatus||'unverified')));row.append(details);
   }
   const parts=[];for(const key of ['enabled','callable','authStatus','runtimeStatus','trustStatus','handlerType','marketplace','disabledReason','model','reasoningEffort'])if(!(view.section==='apps'&&['enabled','callable','authStatus'].includes(key))&&item[key]!==undefined&&item[key]!==null)parts.push(key+': '+display(item[key]));
   if(item.status)parts.push('Status: '+display(item.status));if(item.description&&view.section!=='apps')row.append(node('p',item.description));row.append(node('p',parts.join(' · ')));
   if(view.section==='agents'){
    const open=node('button','Open agent');open.onclick=()=>this.navigate(item.threadId);row.append(open);
    if(!item.canAcceptDirectInput){row.append(node('p','Direct input is unavailable; guide this agent from its parent.'));if(item.parentThreadId){const parent=node('button','Open owning task');parent.onclick=()=>this.navigate(item.parentThreadId);row.append(parent);}}
   }
   if(item.tools?.length){const details=node('details');details.append(node('summary',`${item.toolCount} tools`));for(const tool of item.tools)details.append(node('p',tool.name+(tool.description?' · '+tool.description:'')));row.append(details);}
   if(view.section==='plugins'&&item.inspectable===true)row.append(pluginDetailsButton(this,item));
   if(view.section==='mcp'&&item.canReviewAuthentication)row.append(mcpAuthenticationButton(this,item));
   if(item.configurable){const toggle=node('button',`${item.enabled?'Disable':'Enable'} for this environment`);toggle.onclick=()=>this.change(view,item,toggle);row.append(toggle);}
   this.body.append(row);
  }
  if(view.errorCount)this.body.append(node('p',`${view.errorCount} native catalog entries could not be loaded.`));
  if(['plugins','apps'].includes(view.section))return;
  if(view.nextCursor){const more=node('button','Load more');more.dataset.more='';more.onclick=()=>this.load(view.section,view.nextCursor);this.body.append(more);}
  const refresh=node('button',view.section==='mcp'?'Refresh MCP status':'Reload '+titles[view.section]);refresh.onclick=()=>this.load(view.section);this.body.append(refresh);
 }
 navigate(id){if(!id||!this.current())return;this.dialog.close();this.openThread(id);}
 async change(view,item,button){
  const origin=this.origin;if(!this.current(origin))return;button.disabled=true;
  try{
   let expectedVersion;
   if(view.section!=='skills'){const settings=await this.request('settings',undefined,origin);expectedVersion=settings.configVersion;}
   if(!this.current(origin))return;
   const result=await this.api('/api/native/configuration',{threadId:origin.threadId,section:view.section,id:item.id,enabled:!item.enabled,...(expectedVersion?{expectedVersion}:{})},{scope:origin.scope});
   if(this.current(origin)){await this.load(view.section);if(this.current(origin))this.feedback.textContent=result.notice;}
  }catch(error){if(this.current(origin))this.feedback.textContent=error.message+' Reload this catalog to inspect the outcome before retrying.';}
 }
}
