function panel(create,title){const dialog=create('dialog','execution-dialog');dialog.style.maxWidth='min(90vw,960px)';const close=create('button','','Close');close.onclick=()=>dialog.close();dialog.append(create('h2','',title),close);dialog.addEventListener('close',()=>dialog.remove(),{once:true});document.body.append(dialog);dialog.showModal();return dialog;}
const changed='The selected session changed. Open this view again in the original environment.';
export function commandOutputButton({row,threadId,api,isCurrent=()=>true,create,button}){
 return button('Read command output',()=>{
  const dialog=panel(create,'Command output'),status=create('p'),body=create('pre'),navigation=create('div');body.style.cssText='white-space:pre-wrap;overflow:auto;max-height:60vh';dialog.append(status,body,navigation);let revision=null,offsets=[0],busy=false,qualified=false;
  const current=()=>{if(!dialog.isConnected)return false;if(!isCurrent()){status.textContent=changed;body.textContent='';navigation.replaceChildren();return false;}return true;};
  const read=async(offset,index)=>{if(busy||!current())return;busy=true;status.textContent='Reading retained output…';navigation.replaceChildren();try{
   if(!qualified){const capabilities=await api('/api/native/capabilities?threadId='+encodeURIComponent(threadId));if(!current())return;const output=capabilities.features?.executionOutput;if(output?.state!=='available'){status.textContent=output?.reason||'Retained command output is unavailable on this runtime.';return;}qualified=true;}
   const query=new URLSearchParams({turnId:row.turnId,itemId:row.itemId,offset:String(offset),...(revision?{revision}:{})});const result=await api(`/api/threads/${encodeURIComponent(threadId)}/command-output?${query}`);
   if(!current())return;revision=result.revision;body.textContent=result.text;status.textContent=`${result.source} · ${offset}–${offset+result.text.length} of ${result.totalCharacters} characters`;
   if(index>0)navigation.append(button('Previous page',()=>read(offsets[index-1],index-1)));
   if(result.nextOffset!==null){offsets[index+1]=result.nextOffset;navigation.append(button('Next page',()=>read(result.nextOffset,index+1)));}
  }catch(error){if(current()){status.textContent=error.message;navigation.append(button('Read from beginning',()=>{revision=null;offsets=[0];qualified=false;read(0,0);}));}}finally{busy=false;}};read(0,0);
 });
}
export function openTerminals({threadId,api,isCurrent=()=>true,create,button}){
 const dialog=panel(create,'Background terminals'),status=create('p'),list=create('div');dialog.append(status,list);let serial=0;
 const current=request=>{if(!dialog.isConnected||request!==serial)return false;if(!isCurrent()){serial++;status.textContent=changed;list.replaceChildren();return false;}return true;};
 const read=async(next=null)=>{const request=++serial;if(!current(request))return;status.textContent='Reading terminals…';list.replaceChildren();try{
  const result=await api(`/api/threads/${encodeURIComponent(threadId)}/terminals${next?'?cursor='+encodeURIComponent(next):''}`);if(!current(request))return;
  const mutation=result.features?.backgroundTerminals?.mutation,canTerminate=mutation?.state==='available';
  status.textContent=(result.data.length?'Terminals reported by Codex.':'No background terminals reported.')+(canTerminate?'':' '+(mutation?.reason||'Read-only inspection. Termination is unavailable on this runtime.'));
  for(const terminal of result.data){
   const row=create('section');row.append(create('pre','',terminal.command),create('p','',`${terminal.cwd} · process ${terminal.processId}${terminal.cpuPercent==null?'':` · CPU ${terminal.cpuPercent}%`}`));
   if(canTerminate){const stop=button('Terminate',()=>{if(!current(request))return;stop.disabled=true;const confirm=button('Confirm terminate',async()=>{if(!current(request))return;confirm.disabled=true;try{
    const result=await api(`/api/threads/${encodeURIComponent(threadId)}/terminals/terminate`,{processId:terminal.processId});if(!current(request))return;
    if(!result?.ok||typeof result.terminated!=='boolean')throw Error('Termination receipt missing');status.textContent=result.terminated?'Termination accepted. Refresh to inspect current state.':'No matching background process was terminated. Refresh to inspect current state.';row.remove();
   }catch(error){if(current(request))status.textContent=error.status>=400&&error.status<500?error.message:'Termination unconfirmed. Refresh before another attempt.';}});row.append(create('p','','Stop this exact background process?'),confirm);});row.append(stop);}
   list.append(row);
  }
  if(result.nextCursor)list.append(button('Next terminals',()=>read(result.nextCursor)));
 }catch(error){if(current(request))status.textContent=error.message;}};
 dialog.append(button('Refresh terminals',()=>read()));read();return dialog;
}
