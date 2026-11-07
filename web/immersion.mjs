import {phraseSlots} from './phrase-slots.mjs';
import {flipFlopPlan,transgrammaticFlipPlan} from './flip-flop.mjs';
import {richText} from './presentation.mjs';
import {clientURL} from './paths.mjs';
export class ImmersionLayer {
 constructor(api,base,onStatus){Object.assign(this,{api,base,onStatus,enabled:false,phase:0,plans:new Map(),nodes:new Map(),submitted:new Map(),stable:new WeakMap(),lastSubmitted:new Map(),lexicon:{},epoch:performance.now()});
  this.held=new Map();
  this.observer=new MutationObserver(records=>{if(records.some(r=>!r.target.parentElement?.closest('.immersion-stack')&&![...r.addedNodes].every(n=>n.nodeType===1&&n.classList.contains('immersion-stack'))))this.schedule();});
  this.observer.observe(document.body,{childList:true,subtree:true});document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')this.schedule();});
  fetch(clientURL(base,'interface-grammar.json')).then(r=>r.json()).then(v=>{this.lexicon=v;this.schedule();}).catch(()=>{});
 }
 revision(version){
  if(!version||version===this.planVersion)return;
  this.planVersion=version;this.plans.clear();this.submitted.clear();this.lastSubmitted.clear();
  for(const [element,entry] of this.nodes)if(element.isConnected)this.restore(element,entry);
 }
 async load(){try{this.accept(await this.api('/api/immersion/v2'));}catch(e){this.onStatus('Immersion unavailable · '+e.message);}}
 accept(snapshot){this.revision(snapshot.planVersion);this.enabled=snapshot.enabled===true;for(const plan of snapshot.translations||[])this.plans.set(plan.original,plan);this.onStatus(this.enabled?'English reference ↔50%Italian phrases':'Off');this.schedule();if(this.enabled&&!this.timer)this.timer=setTimeout(()=>this.flip(),10000);if(!this.enabled){clearTimeout(this.timer);this.timer=null;}}
 handle(event){if(event.type!=='immersion')return false;if(!['transgrammatic-english-italian-v2','transgrammatic-english-italian-v3','transgrammatic-english-italian-v4'].includes(event.planVersion))return true;this.revision(event.planVersion);for(const plan of event.translations||[])this.plans.set(plan.original,plan);this.schedule();return true;}
 async setEnabled(enabled){this.accept(await this.api('/api/immersion/v2',{enabled,density:'strong'}));}
 schedule(){clearTimeout(this.scanTimer);this.scanTimer=setTimeout(()=>void this.scan(),160);}
 restore(element,entry){entry.animations?.forEach(a=>a.cancel());if(element.classList.contains('body'))element.replaceChildren(richText(entry.source,entry.resolveUrl));else element.textContent=entry.source;element.style.height='';delete element.dataset.immersionManaged;this.nodes.delete(element);}
 async scan(){
  for(const [element,entry] of this.nodes){if(!element.isConnected)this.nodes.delete(element);else if(!this.enabled)this.restore(element,entry);}
  if(!this.enabled)return;
  const sources=[],elements=[...document.querySelectorAll('.body[data-immersion-text],button:not(:has(img)),summary,h1,h2,h3,.eyebrow,.preview,.card-state,.card-age,label:not(:has(input)):not(:has(select))')];
  for(const element of elements){
   if(element.closest('.immersion-stack')||element.closest('pre')||element.closest('[hidden]')||element.getBoundingClientRect().height===0)continue;
   const existing=this.nodes.get(element),source=element.dataset.immersionText||element.dataset.labelSource||existing?.source||element.textContent.trim();if(!source||source.length>12000||!/\p{L}/u.test(source))continue;
   if(existing&&element.querySelector('.immersion-stack')&&existing.source===source)continue;
   const authored=this.lexicon[source.toLowerCase()],pair=authored?flipFlopPlan(authored):transgrammaticFlipPlan(source,this.plans.get(source));
   if(!pair){element.dataset.immersionCoverage="incomplete";const now=performance.now(),prior=this.stable.get(element);if(!prior||prior.source!==source){this.stable.set(element,{source,at:now});setTimeout(()=>this.schedule(),800);continue;}if(now-prior.at<750)continue;const box=element.getBoundingClientRect();if(box.bottom>0&&box.top<innerHeight&&this.submitted.get(source)!==true){const id=element.dataset.immersionId||'web-label/'+source.slice(0,140);if(now-(this.lastSubmitted.get(id)||-Infinity)<15000){setTimeout(()=>this.schedule(),15000);continue;}this.lastSubmitted.set(id,now);sources.push({id,text:source,kind:element.classList.contains('body')?'message':'ui'});if(sources.length===24)break;}continue;}
   element.dataset.immersionCoverage=pair.eligible===pair.distinct?"paired":"identity-review";
   const identity=element.dataset.immersionId||source,phase=this.held.get(identity)??this.phase;
   const stack=document.createElement('span');stack.className='immersion-stack';stack.dataset.flipPhase=String(phase);stack.dataset.flipEligible=String(pair.eligible);stack.dataset.flipDistinct=String(pair.distinct);
   const current=document.createElement('span');current.className='immersion-current';const rich=element.classList.contains('body'),resolveUrl=element._resolveUrl||((url)=>url);const render=value=>{if(rich)return richText(value,resolveUrl);const fragment=document.createDocumentFragment();fragment.append(document.createTextNode(value));return fragment;};const slots=phraseSlots(pair,phase,render,{interactive:!element.closest('button,a,label,summary,[role=button]')});current.append(slots.root);stack.append(current);element.replaceChildren(stack);element.dataset.immersionManaged='true';
   this.nodes.set(element,{identity,source,pair,current,stack,rich,phase,slots,resolveUrl});
  }
  if(sources.length){sources.forEach(s=>this.submitted.set(s.text,true));try{this.accept(await this.api('/api/immersion/v2/translate',{sources}));}catch(e){this.onStatus('Immersion waiting · '+e.message);}}
 }
 flip(){
  this.timer=null;if(!this.enabled)return;
  if(document.visibilityState==='visible'){
   const anchors=['transcript','work-list'].map(id=>{const root=document.getElementById(id);if(!root)return null;const child=[...root.children].find(n=>n.getBoundingClientRect().bottom>root.getBoundingClientRect().top);return child?{root,child,offset:child.getBoundingClientRect().top-root.getBoundingClientRect().top}:null;}).filter(Boolean);
   for(const [element,entry] of this.nodes){const rect=element.getBoundingClientRect();if(!element.isConnected||rect.bottom<=0||rect.top>=innerHeight||rect.width===0||rect.height===0)continue;
    entry.phase=(entry.phase+1)%entry.pair.forms.length;this.held.set(entry.identity,entry.phase);entry.slots.setPhase(entry.phase);entry.stack.dataset.flipPhase=String(entry.phase);
   }
   for(const anchor of anchors)if(anchor.child.isConnected)anchor.root.scrollTop+=anchor.child.getBoundingClientRect().top-anchor.root.getBoundingClientRect().top-anchor.offset;
   this.phase=(this.phase+1)%4;while(this.held.size>4096)this.held.delete(this.held.keys().next().value);
  }
  this.timer=setTimeout(()=>this.flip(),10000);
 }
}
