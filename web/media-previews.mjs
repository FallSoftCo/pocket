// Gallery metadata and media are public on the owner's private gallery route.
// No device credential, model call or conversation mutation belongs here.
const catalogs=new Map();
export function galleryRoute(value,origin=location.origin){
 try{
  const source=new URL(value,origin),here=new URL(origin);
  if(source.username||source.password||!((['/8bar','/8bar/','/8bar/index.html'].includes(source.pathname))||(source.pathname.startsWith('/8bar/')&&source.pathname!=='/8bar/'&&(source.pathname.endsWith('/')||/\.html$/i.test(source.pathname)))))return null;
  // The compatibility web port mirrors the canonical HTTPS gallery on this host.
  // Never rewrite links to another host or an arbitrary port.
  if(source.origin!==here.origin&&!(source.protocol==='https:'&&!source.port&&source.hostname===here.hostname&&here.protocol==='https:'))return null;
  const isCard=['/8bar','/8bar/','/8bar/index.html'].includes(source.pathname);
  return {source,base:new URL(isCard?'/8bar/':source.pathname,source),local:new URL(isCard?'/8bar/':source.pathname,here),id:isCard?decodeURIComponent(source.hash.slice(1)):null};
 }catch{return null;}
}
const mediaURL=(value,route)=>{
 if(typeof value!=='string'||!value)return null;
 try{const url=new URL(value,route.base);return url.origin===route.base.origin&&url.pathname.startsWith('/8bar/')&&!url.username&&!url.password?new URL(url.pathname+url.search,route.local).href:null;}catch{return null;}
};
async function boundedText(response,limit){
 if(!response.ok)throw Error('Gallery metadata unavailable');
 if(Number(response.headers.get('content-length'))>limit)throw Error('Gallery metadata exceeds preview limit');
 const reader=response.body.getReader(),chunks=[];let size=0;
 try{for(;;){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>limit)throw Error('Gallery metadata exceeds preview limit');chunks.push(value);}}
 catch(error){await reader.cancel();throw error;}finally{reader.releaseLock();}
 const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
 return new TextDecoder().decode(bytes);
}
async function catalog(base){
 const key=base.href,old=catalogs.get(key);if(old&&Date.now()-old.at<60000)return old.promise;
 const promise=(async()=>{
  const get=name=>fetch(new URL(name,base),{credentials:'omit',redirect:'error',signal:AbortSignal.timeout(8000)});
  let response=await get('showcase-previews.json');const legacy=response.status===404;
  if(legacy){await response.body?.cancel();response=await get('showcase-catalog.json');}
  const value=JSON.parse(await boundedText(response,(legacy?16:4)*1024*1024));
  const cards=legacy?value:value?.version===1?value.cards:null;
  if(!Array.isArray(cards)||cards.length>20000)throw Error('Invalid gallery metadata');
  return new Map(cards.filter(card=>card&&typeof card.id==='string').map(card=>[card.id,card]));
 })();
 catalogs.set(key,{at:Date.now(),promise});while(catalogs.size>4)catalogs.delete(catalogs.keys().next().value);
 promise.catch(()=>{if(catalogs.get(key)?.promise===promise)catalogs.delete(key);});return promise;
}
async function pagePreview(route){
 const key=route.local.href,old=catalogs.get(key);if(old&&Date.now()-old.at<60000)return old.promise;
 const promise=(async()=>{
  const response=await fetch(route.local,{credentials:'omit',redirect:'error',signal:AbortSignal.timeout(8000)});
  const type=(response.headers.get('content-type')||'').split(';')[0].trim().toLowerCase();
  if(!['text/html','application/xhtml+xml'].includes(type)){await response.body?.cancel();throw Error('Not a gallery page');}
  // Detached HTML parsing only: no page scripts, styles, frames or remote <base>.
  const document=new DOMParser().parseFromString(await boundedText(response,512*1024),'text/html');
  const title=(document.querySelector('meta[property="og:title"]')?.content||document.title||document.querySelector('h1')?.textContent||'Gallery preview').slice(0,240);
  const candidates=[document.querySelector('meta[property="og:image"]')?.content,...[...document.querySelectorAll('figure img[src],main img[src],article img[src]')].map(i=>i.getAttribute('src')),...[...document.querySelectorAll('img[src]')].map(i=>i.getAttribute('src'))];
  const poster=candidates.find(value=>mediaURL(value,route));
  if(!poster)throw Error('Preview unavailable');
  return {title,poster};
 })();
 catalogs.set(key,{at:Date.now(),promise});while(catalogs.size>16)catalogs.delete(catalogs.keys().next().value);
 promise.catch(()=>{if(catalogs.get(key)?.promise===promise)catalogs.delete(key);});return promise;
}
export class InlineMedia {
 constructor(root){this.root=root;this.observer=new MutationObserver(()=>this.schedule());this.observer.observe(root,{childList:true,subtree:true});this.schedule();}
 schedule(){if(this.scheduled)return;this.scheduled=true;queueMicrotask(()=>{this.scheduled=false;this.scan();});}
 scan(){for(const link of this.root.querySelectorAll('.body a[href]')){
  if(link.dataset.previewChecked||link.querySelector('img')||link.closest('.inline-gallery'))continue;
  link.dataset.previewChecked='true';const route=galleryRoute(link.href);if(!route)continue;
  const preview=document.createElement('figure');preview.className='inline-gallery';link.after(preview);
  const load=async(retry=false)=>{
   if(retry)catalogs.delete(route.local.href);
   preview.textContent='Loading gallery preview…';preview.setAttribute('aria-busy','true');
   try{
    const cards=route.id===null?null:await catalog(route.local);
    const card=cards?(route.id?cards.get(route.id):cards.values().next().value):await pagePreview(route);if(!link.isConnected)return;
    const poster=card&&mediaURL(card.poster,route);if(!poster)throw Error('Preview unavailable');
    const caption=document.createElement('figcaption');caption.textContent=card.title||link.textContent;
    const original=document.createElement('a');original.href=route.source.href;original.target='_blank';original.rel='noopener noreferrer';original.textContent='Open full gallery';caption.append(' · ',original);
    const video=mediaURL(card.video,route),media=document.createElement(video?'video':'img');
    media.referrerPolicy='no-referrer';
    if(video){media.controls=true;media.preload='none';media.playsInline=true;media.poster=poster;media.src=video;media.setAttribute('aria-label',card.title||'Gallery video');}
    else{media.src=poster;media.alt=card.title||link.textContent;media.loading='lazy';media.decoding='async';}
    if(card.width>0&&card.height>0){media.width=card.width;media.height=card.height;}
    const failed=()=>{if(!preview.isConnected)return;preview.replaceChildren(document.createTextNode('Preview unavailable. '));const button=document.createElement('button');button.textContent='Retry';button.onclick=()=>void load(true);preview.append(button);};
    media.addEventListener('error',failed,{once:true});preview.replaceChildren(media,caption);
   }catch{
    if(!link.isConnected)return;
    preview.replaceChildren(document.createTextNode('Preview unavailable. '));const retry=document.createElement('button');retry.textContent='Retry';retry.onclick=()=>void load(true);preview.append(retry);
   }finally{preview.removeAttribute('aria-busy');}
  };
  void load();
 }}
}
