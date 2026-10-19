import {clientBase,clientURL} from './paths.mjs';
const base=clientBase(import.meta.url),motion=matchMedia('(prefers-reduced-motion: reduce)');
const active=new Set(),states=new WeakMap(),pages=new Map(),pending=new Map();
const failed=new Set(),compressed=new Map();const budget=32*1024*1024,compressedBudget=12*1024*1024;let bytes=0,compressedBytes=0,clock=0,manifest;
let draws=0,ticks=0,decodeFailures=0;
const manifestPromise=()=>manifest??=fetch(clientURL(base,'symbols/motion/manifest.json')).then(r=>{if(!r.ok)throw Error('Symbol manifest unavailable');return r.json();});
function staticFrame(state){state.canvas.hidden=true;state.image.hidden=false;}
function wake(){if(!clock&&!document.hidden&&!motion.matches&&[...active].some(s=>s.atlas))clock=requestAnimationFrame(draw);}
async function loadPage(name,atlas,page){
 const key=name+'/'+page;if(failed.has(key))return null;if(pages.has(key)){const hit=pages.get(key);pages.delete(key);pages.set(key,hit);return hit.bitmap;}
 if(pending.has(key))return pending.get(key);
 const promise=(async()=>{
  try{
   let blob=compressed.get(key);
   if(blob){compressed.delete(key);compressed.set(key,blob);}
   else{
    const response=await fetch(clientURL(base,'symbols/motion/'+atlas.pages[page]));if(!response.ok)throw Error('Symbol page unavailable');
    blob=await response.blob();
    while(compressedBytes+blob.size>compressedBudget&&compressed.size){const [old,value]=compressed.entries().next().value;compressed.delete(old);compressedBytes-=value.size;}
    if(blob.size<=compressedBudget){compressed.set(key,blob);compressedBytes+=blob.size;}
   }
   const bitmap=await createImageBitmap(blob),size=bitmap.width*bitmap.height*4;
   while(bytes+size>budget&&pages.size){const [old,entry]=pages.entries().next().value;pages.delete(old);bytes-=entry.size;entry.bitmap.close();}
   pages.set(key,{bitmap,size});bytes+=size;return bitmap;
  }catch{failed.add(key);decodeFailures++;return null;}finally{pending.delete(key);}
 })();pending.set(key,promise);return promise;
}
const observer=new IntersectionObserver(entries=>{
 for(const entry of entries){const state=states.get(entry.target);if(!state)continue;
  if(entry.isIntersecting){active.add(state);state.prepare();wake();}else{active.delete(state);staticFrame(state);}
 }
 if(!active.size&&clock){cancelAnimationFrame(clock);clock=0;}
});
function draw(now){
 clock=0;if(document.hidden||motion.matches)return;ticks++;
 for(const state of active){
  if(!state.node.isConnected){active.delete(state);observer.unobserve(state.node);continue;}
  const atlas=state.atlas;if(!atlas)continue;
  const frame=Math.floor(now*atlas.fps/1000)%atlas.count;if(frame===state.frame)continue;
  const page=Math.floor(frame/atlas.pageFrames),key=state.name+'/'+page,entry=pages.get(key);
  if(!entry){if(!state.loading){state.loading=true;loadPage(state.name,atlas,page).then(bitmap=>{state.loading=false;if(!bitmap){state.atlas=null;staticFrame(state);}});}continue;}
  // Touch current pages before evicting old/offscreen pages; prefetch just the next page.
  pages.delete(key);pages.set(key,entry);
  const next=(page+1)%atlas.pages.length;if(!pages.has(state.name+'/'+next))void loadPage(state.name,atlas,next);
  const local=frame%atlas.pageFrames,ctx=state.context;
  ctx.clearRect(0,0,state.canvas.width,state.canvas.height);
  ctx.drawImage(entry.bitmap,(local%atlas.columns)*atlas.cell,Math.floor(local/atlas.columns)*atlas.cell,atlas.cell,atlas.cell,0,0,state.canvas.width,state.canvas.height);
  state.frame=frame;state.canvas.hidden=false;state.image.hidden=true;draws++;
 }
 wake();
}
function updateMotion(){
 if(clock){cancelAnimationFrame(clock);clock=0;}
 for(const state of active){state.prepare();state.frame=-1;if(document.hidden||motion.matches)staticFrame(state);}
 wake();
}
document.addEventListener('visibilitychange',updateMotion);motion.addEventListener('change',updateMotion);
// IntersectionObserver retains observed detached nodes; explicitly release removed subtrees.
new MutationObserver(records=>{
 for(const record of records)for(const removed of record.removedNodes){
  if(removed.nodeType!==1)continue;
  for(const node of [removed,...removed.querySelectorAll('.modeled-symbol')]){
   const state=states.get(node);if(state&&!node.isConnected){active.delete(state);observer.unobserve(node);}
  }
 }
 if(!active.size&&clock){cancelAnimationFrame(clock);clock=0;}
}).observe(document.documentElement,{childList:true,subtree:true});
export function animatedSymbol(name,{size=32,label=''}={}){
 const node=document.createElement('span');node.className='modeled-symbol';node.style.width=size+'px';node.style.height=size+'px';
 node.setAttribute('role','img');if(label)node.setAttribute('aria-label',label);else node.setAttribute('aria-hidden','true');
 const image=document.createElement('img');image.src=clientURL(base,'symbols/'+name+'.png');image.alt='';image.width=size;image.height=size;
 const canvas=document.createElement('canvas');canvas.hidden=true;const pixels=Math.ceil(size*Math.min(devicePixelRatio||1,2));canvas.width=pixels;canvas.height=pixels;canvas.setAttribute('aria-hidden','true');node.append(image,canvas);
 const context=canvas.getContext('2d',{alpha:true});if(!context)return node;
 const state={node,image,canvas,context,name,atlas:null,frame:-1,loading:false};states.set(node,state);
 // Reduced-motion users need only the existing stills, not multi-megabyte atlases.
 const prepare=()=>{if(motion.matches||state.atlas)return;manifestPromise().then(data=>{state.atlas=data[name];wake();}).catch(()=>{});};
 state.prepare=prepare;observer.observe(node);return node;
}
export function symbolMotionStats(){return {draws,ticks,active:active.size,decodedBytes:bytes,compressedBytes,pending:pending.size,decodeFailures,scheduled:!!clock};}
