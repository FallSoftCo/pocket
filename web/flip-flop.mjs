import {protectedImmersionRanges} from './immersion-literals.mjs';
const lexical=text=>/[\p{L}\p{M}]/u.test(text);
export function flipFlopPlan(units){
 if(!Array.isArray(units)||!units.length||units.some(u=>typeof u.target!=='string'||typeof u.meaning!=='string'||!u.target&&u.meaning))return null;
 const taught=units.filter(u=>!u.literal&&lexical(u.target));
 if(taught.some(u=>!u.meaning.trim()))return null;
 let seed=0;const first=taught[0]?.target||'';for(let i=0;i<first.length;i++)seed=(Math.imul(seed,31)+first.charCodeAt(i))|0;
 let ordinal=0;
 const slots=units.map((u,index)=>{const eligible=!u.literal&&lexical(u.target);return {...u,id:u.id??index,eligible,targetFirst:eligible?((ordinal++ + seed)&1)===0:true};});
 const render=phase=>slots.map(u=>!u.eligible||u.targetFirst!==(phase===1)?u.target:u.meaning).join('');
 const forms=[render(0),render(1)];
 const assignment=phase=>slots.filter(u=>u.eligible).map(u=>u.targetFirst!==(phase===1)?'it':'en');
 return {slots,forms,assignment,eligible:taught.length,distinct:slots.filter(u=>u.eligible&&u.target!==u.meaning).length};
}
export function transgrammaticFlipPlan(source,plan){
 if(plan?.original!==source||plan.planVersion!=='transgrammatic-english-italian-v2'||!Array.isArray(plan.spans))return null;
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
