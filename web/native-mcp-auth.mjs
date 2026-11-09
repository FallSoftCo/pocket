const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
/** URLs stay in this opening only; never open a host callback on a different device. */
export function mcpAuthenticationButton(panel,item){
 const origin=panel.origin,button=node('button','Review sign-in');
 button.onclick=()=>{if(!panel.current(origin))return;const dialog=node('dialog'),body=node('section'),status=node('p');let alive=true,view=null,attemptId=null,busy=false;
  dialog.setAttribute('aria-label','MCP sign-in');dialog.append(node('h2',item.name+' sign-in'),status,body);
  const current=()=>alive&&panel.current(origin)&&dialog.open;
  const close=node('button','Close');close.onclick=()=>dialog.close();dialog.append(close);
  const parentClosed=()=>dialog.close();panel.dialog.addEventListener('close',parentClosed);
  dialog.addEventListener('close',()=>{alive=false;body.replaceChildren();panel.dialog.removeEventListener('close',parentClosed);dialog.remove();});document.body.append(dialog);dialog.showModal();
  const renderReceipt=r=>{body.append(node('p','Original conversation: '+(r.threadId||'host settings')),node('p',r.notice));if(r.authorizationUrl){body.append(node('p','Copy this private sign-in URL to a browser on the original host. The callback may not work from this device.'));const url=node('textarea');url.readOnly=true;url.value=r.authorizationUrl;url.setAttribute('aria-label','Private host sign-in URL');body.append(url);}if(r.canAcknowledge){const inspect=node('button','I inspected the host; review dismissal');inspect.onclick=()=>{if(!current()||busy)return;body.append(node('p','This does not cancel the original flow. Starting again can duplicate an unfinished sign-in.'));const confirm=node('button','Dismiss inspected sign-in receipt');confirm.onclick=async()=>{if(!current()||busy)return;busy=true;confirm.disabled=true;try{await panel.api('/api/native/configuration',{section:'mcp',action:'oauthAcknowledge',threadId:r.threadId,name:item.id,attemptId:r.attemptId,confirmInspected:true},{scope:origin.scope});if(current())status.textContent='Receipt dismissed without cancellation or replay. Reload to inspect current authentication.';}catch{if(current())status.textContent='Dismissal unconfirmed. Reload the original receipt.';}finally{busy=false;}};body.append(confirm);inspect.disabled=true;};body.append(inspect);}};
  const load=async()=>{if(!current()||busy)return;busy=true;status.textContent='Loading…';body.replaceChildren();try{
   view=await panel.api('/api/native/mcp-auth?name='+encodeURIComponent(item.id)+(origin.threadId?'&threadId='+encodeURIComponent(origin.threadId):''),undefined,{scope:origin.scope});if(!current())return;
   status.textContent=view.notice+' Observed authentication: '+view.observedAuthStatus+'. This observation is not an attempt completion receipt.';for(const receipt of view.receipts||[])renderReceipt(receipt);
   if(view.canStart){attemptId=null;const start=node('button','Start sign-in on original host');start.onclick=async()=>{
    if(!current()||busy)return;busy=true;start.disabled=true;attemptId??=crypto.randomUUID();
    try{const receipt=await panel.api('/api/native/configuration',{section:'mcp',action:'oauthStart',threadId:origin.threadId,name:item.id,attemptId,reviewToken:view.reviewToken,confirmHostAuthentication:true},{scope:origin.scope});if(current()){body.replaceChildren();renderReceipt(receipt);status.textContent='Inspect the original sign-in receipt before doing anything else.';}}
    catch{if(current())status.textContent='Sign-in outcome unconfirmed. Reload the original receipt; do not start another attempt.';}finally{busy=false;}
   };body.append(start);}
  }catch(error){if(current())status.textContent=error.message;}finally{busy=false;}};
  const reload=node('button','Reload sign-in receipt');reload.onclick=load;dialog.append(reload);void load();
 };return button;
}
