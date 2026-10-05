import {createHash} from 'node:crypto';
export const IMMERSION_PLAN_VERSION='inline-replacement-v3';
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

const lexical=char=>/[\p{L}\p{M}\p{N}_]/u.test(char),apostrophe=char=>char==="'"||char==='’';
const joins=(left,right)=>{const a=Array.from(left),b=Array.from(right);return lexical(a.at(-1)||'')&&lexical(b[0]||'')||apostrophe(a.at(-1))&&lexical(a.at(-2)||'')&&lexical(b[0]||'')||lexical(a.at(-1)||'')&&apostrophe(b[0])&&lexical(b[1]||'');};
/** Occurrence counts complete eligible quotes, never suffixes or pieces of contractions/graphemes. */
export function resolveImmersionAnchor(text,quote,occurrence){
 if(typeof text!=='string'||typeof quote!=='string'||!quote.length||!Number.isInteger(occurrence)||occurrence<0||occurrence>100)return null;
 const boundaries=new Set([0,text.length]);for(const part of new Intl.Segmenter('it',{granularity:'grapheme'}).segment(text))boundaries.add(part.index);
 let search=0,count=0,start;
 while((start=text.indexOf(quote,search))>=0){const end=start+quote.length;search=start+1;
  if(!boundaries.has(start)||!boundaries.has(end)||joins(text.slice(Math.max(0,start-4),start),text.slice(start,start+4))||joins(text.slice(Math.max(0,end-4),end),text.slice(end,end+4)))continue;
  if(count++===occurrence)return {start,end};
 }
 return null;
}
export const IMMERSION_ROLES=['noun','adjective','determiner','verb','auxiliary','preposition','pronoun','adverb','conjunction','numeral','punctuation','separator','other'];
export const IMMERSION_FEATURES={gender:['masculine','feminine','common','neuter'],number:['singular','plural','invariable'],person:['first','second','third'],tense:['present','past','imperfect','future','conditional'],mood:['indicative','subjunctive','imperative','infinitive','participle','gerund'],aspect:['progressive','perfect','imperfective'],case:['subject','object','indirect','reflexive'],definiteness:['definite','indefinite']};
export const IMMERSION_RELATIONS=['agreesWith','head','auxiliaryOf','negates','subjectOf','objectOf'];
/** Teacher quotes lexical words only; fill only mechanically verified nonlexical gaps. */
export function completeTargetSegments(target,segments){
 if(typeof target!=='string'||!Array.isArray(segments)||!segments.length||segments.length>80)return null;
 if(segments.some(s=>s&&['separator','punctuation'].includes(s.role)))return segments;
 const out=[];let cursor=0;
 const appendGap=gap=>{if(!/^[\s\p{P}\p{S}]*$/u.test(gap))return false;for(const chunk of gap.matchAll(/\s+|[\p{P}\p{S}]+/gu)){const word=chunk[0],start=cursor+chunk.index;let occurrence=0,match;while((match=resolveImmersionAnchor(target,word,occurrence))&&match.start<start)occurrence++;if(!match||match.start!==start)return false;out.push({target:word,occurrence,meaning:'',role:/^\s+$/.test(word)?'separator':'punctuation',features:[],relations:[]});}return true;};
 for(const segment of segments){if(!segment||typeof segment.target!=='string'||!segment.target.length||!Number.isInteger(segment.occurrence)||segment.occurrence<0||segment.occurrence>100)return null;const resolved=resolveImmersionAnchor(target,segment.target,segment.occurrence);if(!resolved)return null;const {start}=resolved;if(start<cursor||!appendGap(target.slice(cursor,start)))return null;out.push(segment);cursor=start+segment.target.length;}
 if(!appendGap(target.slice(cursor)))return null;
 return out;
}
export function validateTargetSegments(target,segments){
 segments=completeTargetSegments(target,segments);if(!segments||segments.length>160)return null;
 const boundaries=new Set([0,target.length]);for(const part of new Intl.Segmenter('it',{granularity:'grapheme'}).segment(target))boundaries.add(part.index);
 const out=[];let cursor=0;
 for(const segment of segments){
  if(!segment||typeof segment!=='object'||typeof segment.target!=='string'||!segment.target.length||!Number.isInteger(segment.occurrence)||segment.occurrence<0||segment.occurrence>100||typeof segment.meaning!=='string'||segment.meaning.length>240||/[\r\n`]/.test(segment.meaning)||!IMMERSION_ROLES.includes(segment.role)||!Array.isArray(segment.features)||segment.features.length>8||!Array.isArray(segment.relations)||segment.relations.length>12)return null;
  const resolved=resolveImmersionAnchor(target,segment.target,segment.occurrence);if(!resolved)return null;const {start,end}=resolved;
  if(start!==cursor||!boundaries.has(start)||!boundaries.has(end))return null;
  const names=new Set();for(const f of segment.features){if(!f||typeof f!=='object'||!IMMERSION_FEATURES[f.name]?.includes(f.value)||names.has(f.name))return null;names.add(f.name);}
  if(['separator','punctuation'].includes(segment.role)&&(segment.meaning.trim()||segment.features.length||segment.relations.length))return null;
  if(segment.role==='separator'&&segment.target.trim())return null;
  if(segment.role==='punctuation'&&!/^[\p{P}\p{S}]+$/u.test(segment.target))return null;
  if(!['separator','punctuation'].includes(segment.role)&&!segment.meaning.trim())return null;
  if(start>0&&/[\p{L}\p{N}_]/u.test(target.slice(start-1,start))&&/[\p{L}\p{N}_]/u.test(segment.target[0])||end<target.length&&/[\p{L}\p{N}_]/u.test(target.slice(end,end+1))&&/[\p{L}\p{N}_]/u.test(segment.target.at(-1)))return null;
  const relations=[];
  for(const relation of segment.relations){
   if(!relation||typeof relation!=='object'||!IMMERSION_RELATIONS.includes(relation.kind)||typeof relation.note!=='string'||relation.note.length>240)return null;
   let toSegment=relation.toSegment;
   if(relation.target!==undefined){
    if(toSegment!==undefined||typeof relation.target!=='string'||!relation.target.length||!Number.isInteger(relation.occurrence)||relation.occurrence<0||relation.occurrence>100)return null;
    if(!resolveImmersionAnchor(target,relation.target,relation.occurrence))return null;
    toSegment=segments.findIndex(s=>s&&s.target===relation.target&&s.occurrence===relation.occurrence);
   }
   if(!Number.isInteger(toSegment)||toSegment<0||toSegment>=segments.length||toSegment===out.length)return null;
   relations.push({kind:relation.kind,toSegment,note:relation.note});
  }
  out.push({start,end,target:segment.target,meaning:segment.meaning,role:segment.role,features:segment.features.map(f=>({name:f.name,value:f.value})),relations});cursor=end;
 }
 if(cursor!==target.length)return null;
 // Agreement claims must point at lexical units, and explicit shared gender/number cannot conflict.
 for(const segment of out)for(const relation of segment.relations){const head=out[relation.toSegment];if(['separator','punctuation'].includes(head.role))return null;
  const directions={auxiliaryOf:[['auxiliary'],['verb']],negates:[['adverb'],['verb','auxiliary']],subjectOf:[['noun','pronoun'],['verb','auxiliary']],objectOf:[['noun','pronoun'],['verb','auxiliary','preposition']],head:[['determiner','adjective','preposition','adverb'],['noun','pronoun','verb','adjective','auxiliary']]};const direction=directions[relation.kind];if(direction&&(!direction[0].includes(segment.role)||!direction[1].includes(head.role)))return null;if(relation.kind==='agreesWith'){let evidence=false;for(const name of ['gender','number','person']){const a=segment.features.find(f=>f.name===name),b=head.features.find(f=>f.name===name);if(a&&b){if(a.value!==b.value&&a.value!=='invariable'&&b.value!=='invariable'&&a.value!=='common'&&b.value!=='common')return null;if(a.value===b.value)evidence=true;}}if(!evidence)return null;}}
 return out;
}

/** Teacher sends phrase replacements once; deterministic source reconstruction belongs here. */
export function deriveImmersionText(source,row){
 if(!row||row.version!==immersionHash(source)||!Array.isArray(row.spans)||row.spans.length>12)return null;
 const spans=[];for(const anchor of row.spans){if(!anchor||typeof anchor.source!=='string'||!anchor.source.length||typeof anchor.target!=='string'||!Number.isInteger(anchor.occurrence)||anchor.occurrence<0||anchor.occurrence>100)return null;const resolved=resolveImmersionAnchor(source,anchor.source,anchor.occurrence);if(!resolved)return null;spans.push({...resolved,target:anchor.target});}
 spans.sort((a,b)=>a.start-b.start);let text='',cursor=0;for(const span of spans){if(span.start<cursor)return null;text+=source.slice(cursor,span.start)+span.target;cursor=span.end;}return text+source.slice(cursor);
}
const lexicalWords=text=>[...text.matchAll(/[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*/gu)].map(m=>({start:m.index,end:m.index+m[0].length}));
/** Selection stays partial without a universal density ceiling or grammatical-purity fallback. */
export function validInlineSelection(source,spans){
 if(!Array.isArray(spans)||spans.some(s=>!s||typeof s.source!=='string'||lexicalWords(s.source).length>6))return false;
 const protectedRanges=protectedImmersionRanges(source);
 const eligible=lexicalWords(source).filter(w=>!protectedRanges.some(p=>w.start<p.end&&w.end>p.start));
 if(eligible.length<8)return true; // Short existing labels may remain whole.
 if(spans.length&&!eligible.some(w=>!spans.some(s=>w.start<s.end&&w.end>s.start)))return false;
 // Reject a whole-sentence anchor even when other sentences keep the source partially untouched.
 for(const sentence of new Intl.Segmenter('en',{granularity:'sentence'}).segment(source)){
  const words=lexicalWords(sentence.segment);if(words.length<2)continue;
  const first=sentence.index+words[0].start,last=sentence.index+words.at(-1).end;
  if(spans.some(s=>s.start<=first&&s.end>=last))return false;
 }
 return true;
}
/** Resolve quoted anchors locally into UTF-16 offsets; never trust model-estimated offsets. */
export function validateImmersionPlan(source,row){
 if(!row||typeof row!=='object'||row.version!==immersionHash(source)||!Array.isArray(row.spans)||row.spans.length>12||typeof row.text!=='string')return null;
 const protectedRanges=protectedImmersionRanges(source),spans=[];
 const boundaries=new Set([0,source.length]);for(const part of new Intl.Segmenter('en',{granularity:'grapheme'}).segment(source))boundaries.add(part.index);
 for(const anchor of row.spans){
  if(!anchor||typeof anchor!=='object'||typeof anchor.source!=='string'||!anchor.source.trim()||anchor.source.length>500||typeof anchor.target!=='string'||!anchor.target.trim()||anchor.target.length>900||!Number.isInteger(anchor.occurrence)||anchor.occurrence<0||anchor.occurrence>100||/[\r\n`]/.test(anchor.source)||/[\r\n`]/.test(anchor.target))return null;
  const resolved=resolveImmersionAnchor(source,anchor.source,anchor.occurrence);if(!resolved)return null;const {start,end}=resolved;
  if(!boundaries.has(start)||!boundaries.has(end))return null;
  if(protectedRanges.some(p=>start<p.end&&end>p.start))return null;
  // Do not splice inside a word or a surrogate pair. Grammar units may contain punctuation.
  if(joins(source.slice(Math.max(0,start-4),start),source.slice(start,start+4))||joins(source.slice(Math.max(0,end-4),end),source.slice(end,end+4)))return null;
  // Grammar teaching evidence is optional; never discard a source-safe replacement or invent cues.
  const targetSegments=validateTargetSegments(anchor.target,anchor.targetSegments)||[];
  spans.push({start,end,targetSegments,source:anchor.source,target:anchor.target,note:typeof anchor.note==='string'?anchor.note.slice(0,240):'',unit:typeof anchor.unit==='string'?anchor.unit:'phrase'});
 }
 spans.sort((a,b)=>a.start-b.start);if(spans.some((s,i)=>i>0&&s.start<spans[i-1].end))return null;
 let text='',cursor=0;for(const span of spans){text+=source.slice(cursor,span.start)+span.target;cursor=span.end;}text+=source.slice(cursor);
 if(text!==row.text||!validInlineSelection(source,spans))return null;
 return {text,spans,planVersion:IMMERSION_PLAN_VERSION,version:row.version,original:source};
}
