import {richText} from './presentation.mjs';
import {clientURL} from './paths.mjs';
function forms(source,plan){
 if(plan?.original!==source||plan.planVersion!=='transgrammatic-english-italian-v2'||!Array.isArray(plan.spans))return null;
 let at=0,support='',target='';
 for(const span of plan.spans){if(!Number.isInteger(span.start)||span.start<at||span.end>source.length||source.slice(span.start,span.end)!==span.source||!Array.isArray(span.targetSegments)||!span.targetSegments.length)return null;
  support+=source.slice(at,span.start);target+=source.slice(at,span.start);let end=0;
  for(const part of span.targetSegments){if(part.start!==end||part.end<part.start||span.target.slice(part.start,part.end)!==part.target||typeof part.meaning!=='string')return null;support+=part.meaning||part.target;end=part.end;}
  if(end!==span.target.length)return null;target+=span.target;at=span.end;
 }
 support+=source.slice(at);target+=source.slice(at);if(target!==plan.text)return null;return {support,target};
}
export class ImmersionLayer {
 constructor(api,base,onStatus){Object.assign(this,{api,base,onStatus,enabled:false,phase:0,plans:new Map(),nodes:new Map(),submitted:new Map(),stable:new WeakMap(),lastSubmitted:new Map(),lexicon:{},epoch:performance.now()});
  this.reduce=matchMedia('(prefers-reduced-motion: reduce)');
  this.observer=new MutationObserver(records=>{if(records.some(r=>!r.target.parentElement?.closest('.immersion-stack')&&![...r.addedNodes].every(n=>n.nodeType===1&&n.classList.contains('immersion-stack'))))this.schedule();});
  this.observer.observe(document.body,{childList:true,subtree:true});document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')this.schedule();});
  fetch(clientURL(base,'interface-grammar.json')).then(r=>r.json()).then(v=>{this.lexicon=v;this.schedule();}).catch(()=>{});
 }
 async load(){try{this.accept(await this.api('/api/immersion/v2'));}catch(e){this.onStatus('Immersion unavailable · '+e.message);}}
 accept(snapshot){this.enabled=snapshot.enabled===true;for(const plan of snapshot.translations||[])this.plans.set(plan.original,plan);this.onStatus(this.enabled?'English in Italian grammar ↔ Italian':'Off');this.schedule();if(this.enabled&&!this.timer)this.timer=setTimeout(()=>this.wave(),3000);if(!this.enabled){clearTimeout(this.timer);this.timer=null;}}
 handle(event){if(event.type!=='immersion')return false;if(event.planVersion!=='transgrammatic-english-italian-v2')return true;for(const plan of event.translations||[])this.plans.set(plan.original,plan);this.schedule();return true;}
 async setEnabled(enabled){this.accept(await this.api('/api/immersion/v2',{enabled,density:'strong'}));}
 schedule(){clearTimeout(this.scanTimer);this.scanTimer=setTimeout(()=>void this.scan(),160);}
 restore(element,entry){entry.animations?.forEach(a=>a.cancel());if(element.classList.contains('body'))element.replaceChildren(richText(entry.source));else element.textContent=entry.source;element.style.height='';delete element.dataset.immersionManaged;this.nodes.delete(element);}
 async scan(){
  for(const [element,entry] of this.nodes){if(!element.isConnected)this.nodes.delete(element);else if(!this.enabled)this.restore(element,entry);}
  if(!this.enabled)return;
  const sources=[],elements=[...document.querySelectorAll('.body[data-immersion-text],button:not(:has(img)),summary,label:not(:has(input)):not(:has(select))')];
  for(const element of elements){
   if(element.closest('.immersion-stack')||element.closest('pre')||element.closest('[hidden]')||element.getBoundingClientRect().height===0)continue;
   const existing=this.nodes.get(element),source=element.dataset.immersionText||existing?.source||element.textContent.trim();if(!source||source.length>12000||!/\p{L}/u.test(source))continue;
   if(existing&&element.querySelector('.immersion-stack')&&existing.source===source)continue;
   const authored=this.lexicon[source.toLowerCase()],pair=authored?{target:authored.map(s=>s.target).join(''),support:authored.map(s=>s.meaning).join('')}:forms(source,this.plans.get(source));
   if(!pair){const now=performance.now(),prior=this.stable.get(element);if(!prior||prior.source!==source){this.stable.set(element,{source,at:now});setTimeout(()=>this.schedule(),800);continue;}if(now-prior.at<750)continue;const box=element.getBoundingClientRect();if(box.bottom>0&&box.top<innerHeight&&this.submitted.get(source)!==true){const id=element.dataset.immersionId||'web-label/'+source.slice(0,140);if(now-(this.lastSubmitted.get(id)||-Infinity)<15000){setTimeout(()=>this.schedule(),15000);continue;}this.lastSubmitted.set(id,now);sources.push({id,text:source,kind:element.classList.contains('body')?'message':'ui'});if(sources.length===24)break;}continue;}
   const stack=document.createElement('span');stack.className='immersion-stack';const current=document.createElement('span'),next=document.createElement('span');current.className='immersion-layer immersion-current';next.className='immersion-layer immersion-next';next.setAttribute('aria-hidden','true');
   const add=(layer,text)=>element.classList.contains('body')?layer.append(richText(text)):layer.append(document.createTextNode(text));add(current,this.phase?pair.target:pair.support);stack.append(current,next);element.replaceChildren(stack);element.dataset.immersionManaged='true';
   const entry={source,pair,current,next,stack,rich:element.classList.contains('body'),phase:this.phase,animations:[]};this.nodes.set(element,entry);if(entry.rich)stack.addEventListener('click',event=>{if(event.target.closest('a')||getSelection()?.toString())return;entry.animations.forEach(a=>a.cancel());entry.phase=1-entry.phase;entry.current.replaceChildren(richText(entry.phase?entry.pair.target:entry.pair.support));entry.next.replaceChildren();entry.stack.classList.remove('immersion-waving');entry.stack.style.height='';});
  }
  if(sources.length){sources.forEach(s=>this.submitted.set(s.text,true));try{this.accept(await this.api('/api/immersion/v2/translate',{sources}));}catch(e){this.onStatus('Immersion waiting · '+e.message);}}
 }
 wave(){
  this.timer=null;if(!this.enabled)return;if(document.visibilityState!=='visible'){this.timer=setTimeout(()=>this.wave(),3000);return;}
  const nextPhase=1-this.phase,visible=[...this.nodes].filter(([el])=>{const r=el.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight;}).sort((a,b)=>a[0].getBoundingClientRect().top-b[0].getBoundingClientRect().top);
  let maximum=0;const top=visible[0]?.[0].getBoundingClientRect().top||0;
  for(const [element,entry] of visible){entry.animations.forEach(a=>a.cancel());const text=nextPhase?entry.pair.target:entry.pair.support;entry.next.replaceChildren(entry.rich?richText(text):document.createTextNode(text));
   const height=Math.max(entry.current.getBoundingClientRect().height,entry.next.getBoundingClientRect().height),line=parseFloat(getComputedStyle(entry.current).lineHeight)||22;
   const delay=Math.max(0,(element.getBoundingClientRect().top-top)/line*115),duration=Math.ceil(height/line)*115+200;maximum=Math.max(maximum,delay+duration);
   if(this.reduce.matches){entry.current.replaceChildren(...entry.next.cloneNode(true).childNodes);entry.next.replaceChildren();entry.phase=nextPhase;continue;}
   entry.stack.style.height=height+'px';entry.stack.style.setProperty('--band',line+'px');entry.stack.classList.add('immersion-waving');
   const animation=entry.stack.animate([{'--reveal':'0px'},{'--reveal':height+line+'px'}],{duration,delay,fill:'both',easing:'linear'});entry.animations=[animation];
   animation.finished.then(()=>{entry.current.replaceChildren(...entry.next.cloneNode(true).childNodes);entry.next.replaceChildren();entry.stack.classList.remove('immersion-waving');entry.stack.style.height='';animation.cancel();entry.phase=nextPhase;}).catch(()=>{});
  }
  this.phase=nextPhase;this.timer=setTimeout(()=>this.wave(),maximum+3000);
 }
}
