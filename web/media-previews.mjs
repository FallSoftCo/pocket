// Resolve existing gallery metadata, never a second agent message or model call.
const catalogs=new Map();
const mediaURL=(value,base)=>{if(typeof value!=='string'||!value)return null;try{const url=new URL(value,base);return url.origin===base.origin&&url.pathname.startsWith(base.pathname)&&!url.username&&!url.password?url.href:null;}catch{return null;}};
async function catalog(base){
 const key=base.href,old=catalogs.get(key);if(old&&Date.now()-old.at<60000)return old.promise;
 const promise=fetch(new URL('showcase-catalog.json',base),{credentials:'same-origin',signal:AbortSignal.timeout(8000)}).then(async response=>{if(!response.ok)throw Error('Gallery metadata unavailable');const data=await response.json();if(!Array.isArray(data)||data.length>20000)throw Error('Invalid gallery metadata');return new Map(data.map(card=>[card.id,card]));});
 catalogs.set(key,{at:Date.now(),promise});while(catalogs.size>4)catalogs.delete(catalogs.keys().next().value);return promise;
}
export class InlineMedia {
 constructor(root){this.root=root;this.observer=new MutationObserver(()=>this.schedule());this.observer.observe(root,{childList:true,subtree:true});this.schedule();}
 schedule(){if(this.scheduled)return;this.scheduled=true;queueMicrotask(()=>{this.scheduled=false;this.scan();});}
 scan(){for(const link of this.root.querySelectorAll('.body a[href]')){
  if(link.dataset.previewChecked||link.querySelector('img')||link.closest('.inline-gallery'))continue;
  link.dataset.previewChecked='true';let url;try{url=new URL(link.href);}catch{continue;}
  if(url.origin!==location.origin||url.pathname!=='/8bar/'||!url.hash)continue;
  const id=decodeURIComponent(url.hash.slice(1)),base=new URL('/8bar/',url);
  void catalog(base).then(cards=>{const card=cards.get(id);if(!link.isConnected||!card)return;const poster=mediaURL(card.poster,base);if(!poster)return;
   const preview=document.createElement('figure');preview.className='inline-gallery';const caption=document.createElement('figcaption');caption.textContent=card.title||link.textContent;
   const image=document.createElement('img');image.src=poster;image.alt=card.title||link.textContent;image.loading='lazy';image.decoding='async';image.referrerPolicy='no-referrer';
   if(card.width>0&&card.height>0){image.width=card.width;image.height=card.height;}
   const video=card.video&&mediaURL(card.video,base);
   if(video){const player=document.createElement('video');player.controls=true;player.preload='none';player.playsInline=true;player.poster=poster;player.src=video;player.setAttribute('aria-label',card.title||'Gallery video');preview.append(player);}else preview.append(image);
   const original=document.createElement('a');original.href=url.href;original.target='_blank';original.rel='noopener noreferrer';original.textContent='Open full gallery';caption.append(' · ',original);preview.append(caption);link.after(preview);
  }).catch(()=>{link.dataset.previewUnavailable='true';});
 }}
}
