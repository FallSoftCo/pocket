/** Quality gate, never a word-deletion filter. Incorrect alignments must be taught again. */
const words=text=>[...String(text).matchAll(/[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*/gu)].map(m=>({word:m[0].toLocaleLowerCase('en'),start:m.index,end:m.index+m[0].length}));
function repetitions(text){
 const tokens=words(text),found=[];
 for(let at=0;at<tokens.length;at++)for(let size=1;size<=3&&at+size*2<=tokens.length;size++){
  const run=tokens.slice(at,at+size*2);
  if(run.some((token,i)=>i&&!/^[\s-]+$/u.test(text.slice(run[i-1].end,token.start))))continue;
  const first=run.slice(0,size).map(t=>t.word),second=run.slice(size).map(t=>t.word);
  if(first.every((word,i)=>word===second[i]))found.push(first.join(' '));
 }
 return found;
}
export function introducedEnglishRepetition(source,target,meaning){
 const repeated=repetitions(meaning);if(!repeated.length)return false;
 const original=new Set(repetitions(source)),italian=repetitions(target);
 return repeated.some(sequence=>{
  if(original.has(sequence)||italian.length)return false;
  // Distinct Italian grammar may legitimately map to repeated English words.
  if(sequence==='had'&&/\bave(?:va|vano|vamo|vi|vo|vate|sse|ssero|ssi|ssimo|ste)\s+avuto\b/iu.test(target))return false;
  if(sequence==='that'&&/\bche\s+quell[oaie]\b/iu.test(target))return false;
  return true;
 });
}
export function validTeachingGlosses(source,spans){
 if(typeof source!=='string'||!Array.isArray(spans))return false;
 return spans.every(span=>{
  if(!span||typeof span.target!=="string"||!Array.isArray(span.targetSegments)||span.targetSegments.some(s=>!s||typeof s.target!=="string"||typeof s.meaning!=="string"))return false;
  const lexical=span.targetSegments.filter(s=>s.meaning?.trim());
  if(lexical.some(s=>introducedEnglishRepetition(span.source||source,s.target,s.meaning)))return false;
  const english=span.targetSegments.map(s=>s.meaning||s.target).join('');
  return !introducedEnglishRepetition(span.source||source,span.target,english);
 });
}
