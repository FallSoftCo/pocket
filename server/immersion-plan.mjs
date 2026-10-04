import {createHash} from 'node:crypto';
export const IMMERSION_PLAN_VERSION='contextual-hybrid-v1';
export const immersionHash=source=>createHash('sha256').update(source).digest('hex');
export const immersionDensity=value=>['starter','balanced','strong'].includes(value)?value:'strong';
export function protectedImmersionRanges(source){
 const ranges=[];
 // Protect complete Markdown fences, then inline code, links, paths, flags and model identifiers.
 let fence=null,begin=0,length=0,offset=0;
 for(const line of source.split('\n')){const m=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);if(!fence&&m&&!(m[1][0]==='`'&&m[2].includes('`'))){fence=m[1][0];begin=offset;length=m[1].length;}else if(fence&&m&&m[1][0]===fence&&m[1].length>=length&&!m[2].trim()){ranges.push({start:begin,end:offset+line.length});fence=null;}offset+=line.length+1;}if(fence)ranges.push({start:begin,end:source.length});
 for(const pattern of [/(`{3,}|~{3,})[\s\S]*?\1/g,/`[^`\n]+`/g,/https?:\/\/[^\s<>]+/g,/(?:\/[\w.@-]+){2,}(?:\/)?/g,/\b[\w@.-]+\.(?:md|mjs|js|ts|tsx|kt|json|py|sh|hiplc|usd|png|exr|apk)\b/g,/\b(?:gpt-\d[\w.-]*|codex-[\w.-]+|o[134](?:-mini)?)\b/gi,/--[\w-]+(?:=[^\s]+)?/g,/(?:[$€£]\s*)?\b\d+(?:[.,:/-]\d+)*(?:\s?%|[hmks])?\b/g])for(const m of source.matchAll(pattern))ranges.push({start:m.index,end:m.index+m[0].length});
 return ranges.sort((a,b)=>a.start-b.start||a.end-b.end);
}
/** Resolve quoted anchors locally into UTF-16 offsets; never trust model-estimated offsets. */
export function validateImmersionPlan(source,row){
 if(row.version!==immersionHash(source)||!Array.isArray(row.spans)||row.spans.length>12||typeof row.text!=='string')return null;
 const protectedRanges=protectedImmersionRanges(source),spans=[];
 const boundaries=new Set([0,source.length]);for(const part of new Intl.Segmenter('en',{granularity:'grapheme'}).segment(source))boundaries.add(part.index);
 for(const anchor of row.spans){
  if(typeof anchor.source!=='string'||!anchor.source.trim()||anchor.source.length>500||typeof anchor.target!=='string'||!anchor.target.trim()||anchor.target.length>900||!Number.isInteger(anchor.occurrence)||anchor.occurrence<0||anchor.occurrence>100||/[\r\n`]/.test(anchor.source)||/[\r\n`]/.test(anchor.target))return null;
  let start=-1,search=0;for(let i=0;i<=anchor.occurrence;i++){start=source.indexOf(anchor.source,search);if(start<0)return null;search=start+anchor.source.length;}
  const end=start+anchor.source.length;
  if(!boundaries.has(start)||!boundaries.has(end))return null;
  if(protectedRanges.some(p=>start<p.end&&end>p.start))return null;
  // Do not splice inside a word or a surrogate pair. Grammar units may contain punctuation.
  if(/[\p{L}\p{N}_]/u.test(source.slice(Math.max(0,start-1),start))&&/[\p{L}\p{N}_]/u.test(anchor.source[0])||/[\p{L}\p{N}_]/u.test(source.slice(end,end+1))&&/[\p{L}\p{N}_]/u.test(anchor.source.at(-1)))return null;
  spans.push({start,end,source:anchor.source,target:anchor.target,note:typeof anchor.note==='string'?anchor.note.slice(0,240):'',unit:typeof anchor.unit==='string'?anchor.unit:'phrase'});
 }
 spans.sort((a,b)=>a.start-b.start);if(spans.some((s,i)=>i>0&&s.start<spans[i-1].end))return null;
 let text='',cursor=0;for(const span of spans){text+=source.slice(cursor,span.start)+span.target;cursor=span.end;}text+=source.slice(cursor);
 if(text!==row.text)return null;
 return {text,spans,planVersion:IMMERSION_PLAN_VERSION,version:row.version,original:source};
}
