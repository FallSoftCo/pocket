// Source-bound manual account action; no background polling or model work.
export function createAccountResetControl({api,storage=localStorage,onUsage=()=>{}}){
 const controls=new Map();
 return ({owner,environment,path})=>{
  const key=`nextcomp.manual-reset.${owner}.${environment}`;
  if(controls.has(key))return controls.get(key);
  const box=document.createElement('div'),button=document.createElement('button'),message=document.createElement('p'),confirm=document.createElement('div');
  button.type='button';button.textContent='Use a banked reset';message.setAttribute('role','status');box.append(button,message,confirm);controls.set(key,box);
  const read=()=>JSON.parse(storage.getItem(key)||'null');
  const submit=async details=>{
   button.disabled=true;confirm.replaceChildren();
   try{
    const original=read();
    if(original&&original.accountToken!==details.accountToken)throw Error('Account changed. Return to the original account to check its reset.');
    const request=original||{id:details.pendingId||crypto.randomUUID(),accountToken:details.accountToken};
    storage.setItem(key,JSON.stringify(request));
    const result=await api(path,request);
    message.textContent=result.message;
    if(result.state!=='pending')storage.removeItem(key);
    button.textContent=result.state==='pending'?'Check reset result':'Use a banked reset';
    if(result.usage)onUsage(environment,result.usage);
   }catch(error){message.textContent=error.message||'Reset result unconfirmed. Check the same request.';button.textContent='Check reset result';}
   finally{button.disabled=false;}
  };
  button.onclick=async()=>{
   button.disabled=true;confirm.replaceChildren();message.textContent='Checking this account…';
   try{
    const details=await api(path);
    if(!details.available){message.textContent=details.message;return;}
    if(details.usage)onUsage(environment,details.usage);
    if(read()||details.pendingId){await submit(details);return;}
    message.textContent='Use one existing banked reset now, even before the allowance runs out? This does not buy credits or enable automatic resets.';
    const yes=document.createElement('button'),no=document.createElement('button');yes.type=no.type='button';yes.textContent='Use reset now';no.textContent='Cancel';
    yes.onclick=()=>submit(details);no.onclick=()=>{confirm.replaceChildren();message.textContent='';};confirm.append(yes,no);
   }catch(error){message.textContent=error.message||'Reset controls unavailable.';}
   finally{button.disabled=false;}
  };
  return box;
 };
}
