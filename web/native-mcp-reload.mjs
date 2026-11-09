const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
export function mcpReloadButton(panel){
 const open=node('button','Review host MCP reload');
 open.onclick=async()=>{
  const origin=panel.origin;if(!panel.current(origin))return;
  const dialog=node('dialog');dialog.setAttribute('aria-label','Host MCP reload');dialog.className='native-settings';
  const body=node('section'),status=node('p');status.setAttribute('role','status');let busy=false,attemptId=null;
  const current=()=>dialog.open&&panel.current(origin);
  const close=node('button','Close');close.onclick=()=>dialog.close();
  const dispose=()=>{dialog.close();dialog.remove();};panel.dialog.addEventListener('close',dispose,{once:true});
  dialog.addEventListener('close',()=>{panel.dialog.removeEventListener('close',dispose);dialog.remove();},{once:true});
  const receipt=r=>{body.append(node('p',`${r.state}: ${r.notice}`));if(r.canAcknowledge){const inspect=node('button','I inspected the host; review dismissal');inspect.onclick=()=>{if(!current()||busy)return;inspect.disabled=true;body.append(node('p','Dismissal does not cancel the reload. A new reload can repeat changes if the original outcome remains unknown.'));const dismiss=node('button','Dismiss inspected reload receipt');dismiss.onclick=async()=>{if(!current()||busy)return;busy=true;dismiss.disabled=true;try{const result=await panel.api('/api/native/configuration',{section:'mcp',action:'reloadAcknowledge',threadId:r.threadId,attemptId:r.attemptId,confirmInspected:true},{scope:origin.scope});if(current()){body.replaceChildren();receipt(result);status.textContent='Receipt dismissed without replay. Check reload receipts before a new action.';}}catch{if(current())status.textContent='Dismissal unconfirmed. Check reload receipts.';}finally{busy=false;}};body.append(dismiss);};body.append(inspect);}};
  const load=async()=>{if(!current()||busy)return;busy=true;body.replaceChildren();status.textContent='Inspecting original host…';try{
   const view=await panel.api('/api/native/configuration?section=mcp&reloadReview=1'+(origin.threadId?'&threadId='+encodeURIComponent(origin.threadId):''),undefined,{scope:origin.scope});if(!current())return;
   status.textContent=view.notice;for(const r of view.receipts||[])receipt(r);
   if(attemptId&&view.receipts?.some(r=>r.attemptId===attemptId&&!['dispatching','unknown'].includes(r.state)))attemptId=null;
   if(view.canReload){const start=node('button','Reload MCP across this host');start.onclick=async()=>{if(!current()||busy)return;busy=true;start.disabled=true;attemptId??=crypto.randomUUID();try{
    const r=await panel.api('/api/native/configuration',{section:'mcp',action:'reload',threadId:origin.threadId,attemptId,reviewToken:view.reviewToken,confirmHostWide:true},{scope:origin.scope});if(current()){body.replaceChildren();receipt(r);status.textContent='Original reload receipt. Check MCP status separately for connection readiness.';}
   }catch{if(current()){body.replaceChildren();status.textContent='Reload delivery unconfirmed. Check original reload receipts; do not submit again blindly.';}}finally{busy=false;}};body.append(start);}
  }catch{if(current())status.textContent='Reload review unavailable. No reload was submitted by this inspection.';}finally{busy=false;}};
  const reload=node('button','Check reload receipts');reload.onclick=load;
  dialog.append(node('h2','Host MCP reload'),node('p',panel.source.textContent),status,body,reload,close);document.body.append(dialog);dialog.showModal();await load();
 };return open;
}
