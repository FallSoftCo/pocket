function panel(create,title){const dialog=create('dialog','execution-dialog');dialog.style.maxWidth='min(90vw,960px)';const close=create('button','','Close');close.onclick=()=>dialog.close();dialog.append(create('h2','',title),close);dialog.addEventListener('close',()=>dialog.remove(),{once:true});document.body.append(dialog);dialog.showModal();return dialog;}
export function commandOutputButton({row,threadId,api,create,button}){
 return button('Read command output',()=>{
  const dialog=panel(create,'Command output'),status=create('p'),body=create('pre'),navigation=create('div');body.style.cssText='white-space:pre-wrap;overflow:auto;max-height:60vh';dialog.append(status,body,navigation);let revision=null,offsets=[0],busy=false;
  const read=async(offset,index)=>{if(busy)return;busy=true;status.textContent='Reading retained output…';navigation.replaceChildren();try{
   const query=new URLSearchParams({turnId:row.turnId,itemId:row.itemId,offset:String(offset),...(revision?{revision}:{})});const result=await api(`/api/threads/${encodeURIComponent(threadId)}/command-output?${query}`);
   if(!dialog.isConnected)return;revision=result.revision;body.textContent=result.text;status.textContent=`${result.source} · ${offset}–${offset+result.text.length} of ${result.totalCharacters} characters`;
   if(index>0)navigation.append(button('Previous page',()=>read(offsets[index-1],index-1)));
   if(result.nextOffset!==null){offsets[index+1]=result.nextOffset;navigation.append(button('Next page',()=>read(result.nextOffset,index+1)));}
  }catch(error){status.textContent=error.message;navigation.append(button('Read from beginning',()=>{revision=null;offsets=[0];read(0,0);}));}finally{busy=false;}};read(0,0);
 });
}
export function openTerminals({threadId,api,create,button}){
 const dialog=panel(create,'Background terminals'),status=create('p'),list=create('div');dialog.append(status,list);let cursor=null;
 const read=async(next=null)=>{status.textContent='Reading terminals…';try{const result=await api(`/api/threads/${encodeURIComponent(threadId)}/terminals${next?'?cursor='+encodeURIComponent(next):''}`);if(!dialog.isConnected)return;cursor=result.nextCursor;list.replaceChildren();status.textContent=result.data.length?'Terminals reported by Codex.':'No background terminals reported.';
  for(const terminal of result.data){const row=create('section');row.append(create('pre','',terminal.command),create('p','',`${terminal.cwd} · process ${terminal.processId}${terminal.cpuPercent===null?'':` · CPU ${terminal.cpuPercent}%`}`));const stop=button('Terminate',()=>{stop.disabled=true;const confirm=button('Confirm terminate',async()=>{confirm.disabled=true;try{const result=await api(`/api/threads/${encodeURIComponent(threadId)}/terminals/terminate`,{processId:terminal.processId});if(!result?.ok||typeof result.terminated!=='boolean')throw Error('Termination receipt missing');status.textContent=result.terminated?'Termination accepted. Refresh to inspect current state.':'No matching background process was terminated. Refresh to inspect current state.';row.remove();}catch(error){status.textContent=error.status>=400&&error.status<500?error.message:'Termination unconfirmed. Refresh before another attempt.';}});row.append(create('p','','Stop this exact background process?'),confirm);});row.append(stop);list.append(row);}
  if(cursor)list.append(button('Next terminals',()=>read(cursor)));
 }catch(error){status.textContent=error.message;}};
 dialog.append(button('Refresh terminals',()=>read()));read();
}
