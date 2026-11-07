import {renderNativeRequest} from './native-request.mjs';
// Keyed live requests retain a typed answer when another agent/question changes.
export class RequestList {
 constructor({holder,create,button,api,refresh}){Object.assign(this,{holder,create,button,api,refresh,cards:new Map()});}
 update(scope,requests){
  let cursor=this.holder.firstChild;const place=box=>{if(box!==cursor)this.holder.insertBefore(box,cursor);cursor=box.nextSibling;};
  const keep=new Set();for(const request of requests){const key=scope+'/'+request.id;keep.add(key);let entry=this.cards.get(key);const signature=JSON.stringify(request);
   if(entry&&(entry.signature===signature||entry.state!=='pending')){place(entry.box);continue;}
   const prior=entry?Object.fromEntries([...entry.box.querySelectorAll('[data-question]')].map(n=>[n.dataset.question,n.multiple?JSON.stringify([...n.selectedOptions].map(o=>o.value)):n.value])):{};
   if(entry?.box===cursor)cursor=entry.box.nextSibling;
   const focused=entry?.box.contains(document.activeElement)?document.activeElement:null,focusField=focused?.dataset.question,selection=focused?[focused.selectionStart,focused.selectionEnd]:null;
   entry?.box.remove();
   const box=this.create('article','request-card');box.dataset.requestId=String(request.id);entry={box,signature,state:'pending'};this.cards.set(key,entry);
   const status=this.create('p','request-status');status.setAttribute('role','status');
   const controls=[];const send=async body=>{
    if(entry.state!=='pending')return;entry.state='sending';controls.forEach(n=>n.disabled=true);status.textContent='Submitting your decision…';
    try{await this.api('/api/requests/'+encodeURIComponent(request.id)+'/answer',{...body,...(request._pocketUI?.token?{requestToken:request._pocketUI.token}:{})});entry.state='submitted';status.textContent='Decision submitted.';}
    catch(error){entry.state=error.status>=400&&error.status<500?'pending':'unconfirmed';status.textContent=entry.state==='pending'?error.message:'Delivery unconfirmed. Check current work before trying again.';if(entry.state==='pending')controls.forEach(n=>n.disabled=false);else box.append(this.button('Check current state',()=>this.refresh()));return;}
    try{await this.refresh();}catch{status.textContent='Decision submitted. Live status is reconnecting.';}
   };
   const action=(label,fn)=>{const b=this.button(label,fn);controls.push(b);box.append(b);return b;};
   if(/requestUserInput$/.test(request.method)){
    for(const question of request.params?.questions||[]){const label=this.create('label','question-label',question.question),input=this.create('input');input.placeholder='Your answer';input.type=question.isSecret?'password':'text';input.dataset.question=question.id;input.setAttribute('aria-label',question.question);input.value=prior[question.id]||'';label.append(input);box.append(label);controls.push(input);
     for(const option of [...(question.options||[]),...(question.isOther&&question.options?.length?[{label:'None of the above'}]:[])]){const choice=action(option.label,()=>{input.value=option.label;input.dispatchEvent(new Event('input',{bubbles:true}));});choice.classList.add('question-option');choice.setAttribute('aria-label',option.label);if(option.description)choice.append(this.create('small','',option.description));}
    }
    action('Submit answers',()=>send({answers:Object.fromEntries([...box.querySelectorAll('[data-question]')].map(n=>[n.dataset.question,n.multiple?JSON.stringify([...n.selectedOptions].map(o=>o.value)):n.value]))}));action('Skip',()=>send({skip:true}));
   }else renderNativeRequest({request,box,create:this.create,action,send,controls,prior});
   box.append(status);place(box);if(focusField){const input=[...box.querySelectorAll('[data-question]')].find(n=>n.dataset.question===focusField);input?.focus({preventScroll:true});if(input&&selection&&typeof input.setSelectionRange==='function'){try{input.setSelectionRange(...selection);}catch{}}}
  }
  for(const [key,entry] of this.cards)if(!keep.has(key)){entry.box.remove();if(key.startsWith(scope+'/'))this.cards.delete(key);}
  while(this.cards.size>256){const key=[...this.cards.keys()].find(key=>!keep.has(key));if(!key)break;this.cards.get(key).box.remove();this.cards.delete(key);}
 }
}
