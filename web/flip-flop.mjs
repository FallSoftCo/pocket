import {protectedImmersionRanges} from './immersion-literals.mjs';
const lexical=text=>/[\p{L}\p{M}]/u.test(text);
export function flipFlopPlan(units){
 if(!Array.isArray(units)||!units.length||units.some(u=>typeof u.target!=='string'||typeof u.meaning!=='string'||!u.target&&u.meaning))return null;
 const taught=units.filter(u=>!u.literal&&lexical(u.target));
 if(taught.some(u=>!u.meaning.trim()))return null;
 const slots=units.map((u,index)=>({...u,id:u.id??index,eligible:!u.literal&&lexical(u.target)}));
 const eligible=slots.filter(u=>u.eligible);
 const weight=u=>(u.target.match(/[\p{L}\p{M}]+/gu)||[]).length;
 const total=eligible.reduce((n,u)=>n+weight(u),0);let cut=eligible.length,covered=0,best=Infinity;
 for(let i=1;i<eligible.length;i++){covered+=weight(eligible[i-1]);const distance=Math.abs(total/2-covered);if(distance<best){best=distance;cut=i;}}
 const first=new Set(eligible.slice(0,cut)),second=new Set(eligible.slice(cut));
 const selections=[new Set(),first,new Set(),second];
 const render=selected=>slots.map(u=>!u.eligible||selected.has(u)?u.target:u.meaning).join('');
 const english=render(selections[0]);const forms=[english,render(first),english,render(second)];
 const assignment=phase=>eligible.map(u=>selections[phase].has(u)?'it':'en');
 return {slots,forms,assignment,eligible:taught.length,distinct:slots.filter(u=>u.eligible&&u.target!==u.meaning).length};
}
export function transgrammaticFlipPlan(source,plan){
 if(plan?.original!==source||!['transgrammatic-english-italian-v2','transgrammatic-english-italian-v3'].includes(plan.planVersion)||!Array.isArray(plan.spans))return null;
 const units=[];let at=0;const protectedRanges=protectedImmersionRanges(source);
 const uncovered=(start,end)=>[...source.slice(start,end).matchAll(/[\p{L}\p{M}]/gu)].some(m=>!protectedRanges.some(p=>start+m.index>=p.start&&start+m.index<p.end));
 for(const span of plan.spans){
  if(!Number.isInteger(span.start)||span.start<at||span.end>source.length||source.slice(span.start,span.end)!==span.source||!Array.isArray(span.targetSegments)||!span.targetSegments.length)return null;
  if(uncovered(at,span.start))return null;
  units.push({target:source.slice(at,span.start),meaning:source.slice(at,span.start),literal:true});let end=0;
  for(const part of span.targetSegments){if(part.start!==end||part.end<part.start||span.target.slice(part.start,part.end)!==part.target||typeof part.meaning!=='string')return null;units.push({target:part.target,meaning:part.meaning,literal:!lexical(part.target)});end=part.end;}
  if(end!==span.target.length)return null;at=span.end;
 }
 if(uncovered(at,source.length))return null;
 units.push({target:source.slice(at),meaning:source.slice(at),literal:true});
 if(units.map(u=>u.target).join('')!==plan.text)return null;return flipFlopPlan(units);
}
