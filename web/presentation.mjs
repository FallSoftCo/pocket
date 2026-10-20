import {clientBase,clientURL} from './paths.mjs';
const client=clientBase(import.meta.url);
const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
const safeURL=value=>{try{const u=new URL(String(value).startsWith('/api/')?clientURL(client,value):value,location.href);return ['https:','http:'].includes(u.protocol)?u.href:null;}catch{return null;}};
function inline(text,parent){
 const pattern=/(!?\[([^\]\n]+)\]\(([^\s)]+)\)|`([^`\n]+)`|\*\*([^*\n]+)\*\*|__([^_\n]+)__|\*([^*\n]+)\*)/g;let at=0;
 for(const m of text.matchAll(pattern)){parent.append(document.createTextNode(text.slice(at,m.index)));at=m.index+m[0].length;
  if(m[2]){const url=safeURL(m[3]);if(!url){parent.append(document.createTextNode(m[0]));continue;}if(m[0].startsWith('!')){const image=node('img');image.src=url;image.alt=m[2];image.loading='lazy';image.decoding='async';image.referrerPolicy='no-referrer';const a=node('a');a.href=url;a.target='_blank';a.rel='noopener noreferrer';a.append(image);parent.append(a);}else{const a=node('a',m[2]);a.href=url;a.target='_blank';a.rel='noopener noreferrer';parent.append(a);if(/\.(png|jpe?g|webp|gif)(\?|$)/i.test(url)){const image=node('img');image.src=url;image.alt=m[2];image.loading='lazy';image.decoding='async';image.referrerPolicy='no-referrer';parent.append(image);}}}
  else parent.append(node(m[4]?'code':m[5]||m[6]?'strong':'em',m[4]||m[5]||m[6]||m[7]));
 }parent.append(document.createTextNode(text.slice(at)));
}
const cells=line=>line.trim().replace(/^\||\|$/g,'').split(/(?<!\\)\|/).map(x=>x.trim().replace(/\\\|/g,'|'));
export function richText(value){
 const root=document.createDocumentFragment(),lines=String(value||'').split('\n');let i=0;
 while(i<lines.length){const line=lines[i];if(!line.trim()){i++;continue;}
  if(/^\s*```/.test(line)){const lang=line.trim().slice(3).trim(),code=[];i++;while(i<lines.length&&!/^\s*```/.test(lines[i]))code.push(lines[i++]);i++;const pre=node('pre'),c=node('code',code.join('\n'));if(lang)c.dataset.language=lang;pre.append(c);root.append(pre);continue;}
  if(i+1<lines.length&&line.includes('|')&&/^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(lines[i+1])){
   const wrapper=node('div');wrapper.className='table-scroll';wrapper.tabIndex=0;const table=node('table'),head=node('thead'),tr=node('tr');for(const c of cells(line)){const th=node('th');th.scope='col';inline(c,th);tr.append(th);}head.append(tr);table.append(head);const body=node('tbody');i+=2;
   while(i<lines.length&&lines[i].includes('|')&&lines[i].trim()){const row=node('tr');for(const c of cells(lines[i++])){const td=node('td');inline(c,td);row.append(td);}body.append(row);}table.append(body);wrapper.append(table);root.append(wrapper);continue;
  }
  const h=/^(#{1,6})\s+(.*)$/.exec(line);if(h){const n=node('h'+h[1].length);inline(h[2],n);root.append(n);i++;continue;}
  if(/^\s*([-*+] |\d+[.)] )/.test(line)){const ordered=/^\s*\d/.test(line),list=node(ordered?'ol':'ul');while(i<lines.length&&/^\s*([-*+] |\d+[.)] )/.test(lines[i])){const item=node('li');inline(lines[i++].replace(/^\s*(?:[-*+] |\d+[.)] )/,''),item);list.append(item);}root.append(list);continue;}
  if(/^>\s?/.test(line)){const q=node('blockquote');inline(line.replace(/^>\s?/,''),q);root.append(q);i++;continue;}
  const p=node('p');inline(line,p);i++;while(i<lines.length&&lines[i].trim()&&!/^\s*(?:```|#{1,6}\s|>\s|[-*+]\s|\d+[.)]\s)/.test(lines[i])&&!(i+1<lines.length&&/^\s*\|?\s*:?-{3}/.test(lines[i+1]))){p.append(node('br'));inline(lines[i++],p);}root.append(p);
 }return root;
}
export function foldRuns(rows){const output=[];let run=null;for(const row of rows){
 if(row.kind==='activity'){if(!run){run={id:'runs/'+row.id,kind:'runs',rows:[]};output.push(run);}run.rows.push(row);}
 else if(['turn','turnEnd'].includes(row.kind)){if(row.text){run=null;output.push({...row,kind:'message',title:'Turn stopped'});}else if(run)run.rows.push(row);}
 else{run=null;output.push(row);}
 }return output;}
export function usageSummary(usage){
 const weekly=usage?.weekly,remaining=Number(weekly?.remainingPercent),valid=weekly?.remainingPercent!=null&&Number.isFinite(remaining)&&remaining>=0&&remaining<=100;
 const percent=valid?`${Math.round(remaining*10)/10}% left`:'Usage unavailable',reset=Number(weekly?.resetsAt);
 return {compact:percent,remaining:valid?remaining:null,reset:reset>0?`Resets ${new Date(reset*1000).toLocaleString()}`:'Reset time unavailable',stale:usage?.stale===true||!usage?.updatedAt||Date.now()-usage.updatedAt>120000,credits:usage?.credits?.balance!=null?`${usage.credits.balance} credits`:usage?.credits?.unlimited?'Unlimited credits':'Credits unavailable',continuation:usage?.continuation?.label||'Credit continuation status unavailable'};
}
export function sessionState(thread){const type=thread?.status?.type||'unavailable';if(type==='active')return {label:'Working',tone:'working'};if(type==='pending'||thread?.pendingCount>0)return {label:'Needs you',tone:'attention'};if(type==='idle')return {label:'Idle',tone:'idle'};return {label:type==='unavailable'?'Status unavailable':type,tone:'unknown'};}
export function canonicalPage(previous,incoming,{older=false}={}){
 const key=r=>r.turnId||r.id,order=[],groups=new Map(),versions=new Map(previous.map(r=>[r.id,r]));
 const incomingIds=new Set(incoming.map(r=>r.id)),users=new Set(incoming.filter(r=>r.kind==='user'&&r.clientId).map(r=>r.clientId));
 const old=previous.filter(r=>!(r.kind==='user'&&r.clientId&&users.has(r.clientId)&&!incomingIds.has(r.id)));
 for(const r of (older?[...incoming,...old]:[...old,...incoming]))if(!groups.has(key(r))){order.push(key(r));groups.set(key(r),true);}
 const group=rows=>{const map=new Map();for(const r of rows){const k=key(r);if(!map.has(k))map.set(k,[]);map.get(k).push(r);}return map;};
 const priorGroups=group(old),freshGroups=group(incoming),out=[];
 for(const turn of order){
  const kept=priorGroups.get(turn)||[],page=freshGroups.get(turn)||[];
  if(!page.length){out.push(...kept);continue;}
  const retained=kept.filter(r=>!incomingIds.has(r.id)&&!['turn','turnEnd'].includes(r.kind));
  const fresh=page.map(r=>{const prior=versions.get(r.id);return prior&&(prior.version||0)>(r.version||0)?prior:r;});
  // Older missing items remain before the authoritative current page within
  // their turn. A historical page precedes already-retained newer items.
  const header=fresh.filter(r=>r.kind==='turn'),end=fresh.filter(r=>r.kind==='turnEnd');
  const content=fresh.filter(r=>!['turn','turnEnd'].includes(r.kind));
  out.push(...header,...(older?[...content,...retained]:[...retained,...content]),...end);
 }
 return out;
}
export class ReadLanes {
 constructor(){this.heads=new Map();this.history=new Map();}
 run(key,older,read,{invalidate=true}={}){
  const lane=older?this.history:this.heads,active=lane.get(key);if(active){if(!older&&invalidate)active.dirty=true;return active.promise;}
  const entry={dirty:false,promise:null};
  entry.promise=Promise.resolve().then(async()=>{let result;do{entry.dirty=false;result=await read();}while(!older&&entry.dirty);return result;}).finally(()=>{if(lane.get(key)===entry)lane.delete(key);});lane.set(key,entry);return entry.promise;
 }
}
