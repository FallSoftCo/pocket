const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
const button=(label,fn)=>{const n=node('button',label);n.onclick=fn;return n;};
const yesNo=value=>value===true?'Yes':value===false?'No':'Unavailable';
const policyLabel=value=>({AVAILABLE:'Installation allowed',NOT_AVAILABLE:'Installation restricted',INSTALLED_BY_DEFAULT:'Managed installation'}[value]||'Installation policy unavailable');
const authLabel=value=>({ON_INSTALL:'Authentication during setup',ON_USE:'Authentication when used'}[value]||'Authentication policy unavailable');
const operationLabel=value=>({dispatching:'Waiting for Codex',acknowledged:'Reply received',unknown:'Outcome unconfirmed'}[value]||'Status unavailable');
// This pane lives inside the existing settings dialog. Every callback retains
// its opening and source; tab changes and close/reopen invalidate old callbacks.
export function pluginDetailsButton(panel,item,label='Review plugin'){
 const origin=panel.origin,serial=panel.serial;
 return button(label,()=>{
  if(!panel.current(origin)||panel.serial!==serial)return;
  panel.pluginFooter?.replaceChildren();panel.dialog.classList.remove('plugin-catalog');
  const pane=new PluginDetails(panel,item.key,origin,++panel.serial);return pane.load();
 });
}
class PluginDetails {
 constructor(panel,key,origin,serial){Object.assign(this,{panel,key,origin,serial});this.loadRevision=0;}
 current(){return this.panel.current(this.origin)&&this.panel.serial===this.serial;}
 async load(){
  if(!this.current())return;const revision=++this.loadRevision,current=()=>this.current()&&this.loadRevision===revision;this.view=null;this.panel.body.replaceChildren();this.panel.feedback.textContent='Loading plugin details…';
  try{
   const view=await this.panel.api('/api/native/plugins/detail?key='+encodeURIComponent(this.key)+(this.origin.threadId?'&threadId='+encodeURIComponent(this.origin.threadId):''),undefined,{scope:this.origin.scope});
   if(!current())return;this.view=view;this.panel.feedback.textContent='';this.render();
  }catch(error){if(!current())return;this.panel.feedback.textContent=error.message;try{const saved=await this.panel.api('/api/native/plugins/receipts?key='+encodeURIComponent(this.key),undefined,{scope:this.origin.scope});if(current())for(const receipt of saved.receipts||[])this.panel.body.append(node('p',`${receipt.name} · ${receipt.action} · ${operationLabel(receipt.state)}: ${receipt.notice}`));}catch{}if(current())this.panel.body.append(button('Reload plugin details',()=>this.load()));}
 }
 render(){
  const {panel,view}=this,d=view.detail,revision=this.loadRevision,confirm=(...args)=>{if(this.loadRevision===revision)return this.confirm(...args);};panel.body.replaceChildren(node('h3',d.name),node('p',d.marketplace),node('p',`Installed: ${yesNo(d.installed)} · Enabled: ${yesNo(d.enabled)} · ${policyLabel(d.installPolicy)} · ${authLabel(d.authPolicy)}`));
  if(d.developer)panel.body.append(node('p','Developer: '+d.developer));
  if(d.description)panel.body.append(node('p',d.description));
  for(const [label,values]of [['Capabilities',d.capabilities],['Skills',d.skills],['Hooks',d.hooks],['MCP servers',d.mcpServers],['Apps',d.apps?.map(a=>a.name||a.id)]])if(values?.length)panel.body.append(node('p',`${label}: ${values.join(', ')}`));
  panel.body.append(node('p','Readiness is unverified. Review MCP and Apps status after installation; host authentication cannot be completed here.'));
  if(view.reason)panel.body.append(node('p',view.reason));
  for(const receipt of view.receipts||[]){
   const row=node('article');row.append(node('p',`${receipt.action} · ${operationLabel(receipt.state)}${receipt.acknowledgedAt?' · reviewed':''}`),node('p',receipt.notice));
   if(receipt.appsNeedingAuth?.length)row.append(node('p','Apps requiring authentication: '+receipt.appsNeedingAuth.map(a=>a.name||a.id).join(', ')));
   if(receipt.canAcknowledge===true)row.append(button('Review uncertain outcome',()=>confirm('acknowledge',receipt.attemptId)));
   panel.body.append(row);
  }
  if(view.canInstall===true)panel.body.append(button('Review installation',()=>confirm('install')));
  if(view.canUninstall===true)panel.body.append(button('Review removal',()=>confirm('uninstall')));
  panel.body.append(button('Reload plugin details',()=>this.load()),button('Back to plugins',()=>{if(this.current())void panel.load('plugins');}));
 }
 confirm(action,attemptId){
  if(!this.current())return;
  const {panel,view}=this,revision=this.loadRevision;panel.body.replaceChildren(node('h3',view.detail.name));
  panel.body.append(node('p',action==='install'?view.effects:action==='uninstall'?view.removalEffects:'A missing catalog entry does not prove the earlier operation had no effect or has ended. Acknowledge only after reviewing this environment and account. This keeps the original outcome unconfirmed and never replays it.'));
  if(action==='install'){panel.body.append(node('p',view.detail.description||''),node('p','Capabilities: '+[...(view.detail.capabilities||[]),...(view.detail.skills||[]),...(view.detail.hooks||[]),...(view.detail.mcpServers||[])].join(', ')));for(const [label,url]of Object.entries(view.detail.links||{})){const link=node('a',label);link.href=url;link.target='_blank';link.rel='noopener noreferrer';panel.body.append(link);}}
  let check;
  if(action==='install'&&view.detail.mustShowInstallationInterstitial){const label=node('label');check=node('input');check.type='checkbox';label.append(check,document.createTextNode('I reviewed the required installation notice, plugin capabilities and host authentication effects.'));panel.body.append(label);}
  const confirm=button(action==='install'?'Confirm installation':action==='uninstall'?'Confirm removal':'Acknowledge uncertain outcome',()=>{
   if(!this.current()||this.loadRevision!==revision||check&&!check.checked)return;
   void this.submit(action,attemptId||globalThis.crypto.randomUUID(),view,check?.checked===true);
  });
  if(check){confirm.disabled=true;check.onchange=()=>confirm.disabled=!check.checked;}
  panel.body.append(confirm,button('Go back',()=>{if(this.current()&&this.loadRevision===revision)this.render();}));
 }
 async submit(action,attemptId,view,confirmInterstitial){
  if(!this.current())return;const revision=++this.loadRevision,current=()=>this.current()&&revision===this.loadRevision;
  this.panel.body.replaceChildren();this.panel.feedback.textContent='Awaiting native acknowledgement…';
  try{
   const receipt=await this.panel.api('/api/native/configuration',{section:'plugins',threadId:this.origin.threadId,key:this.key,reviewToken:view.reviewToken,action,attemptId,confirmEffects:true,confirmInterstitial},{scope:this.origin.scope});
   if(!current())return;this.panel.feedback.textContent=receipt.notice;
  }catch{if(!current())return;this.panel.feedback.textContent='Delivery unconfirmed. Reload details to inspect the original attempt. Do not repeat the operation.';}
  if(current())this.panel.body.append(button('Reload plugin details',()=>this.load()));
 }
}
