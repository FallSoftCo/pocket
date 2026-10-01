const MORE='Open Pocket for the full update.';
export function cleanSpeech(value){
  return String(value||'').replace(/```[\s\S]*?(?:```|$)/g,' ')
    .replace(/`[^`]*`/g,' ').replace(/\[([^\]]+)\]\([^)]*\)/g,'$1')
    .replace(/https?:\/\/\S+|(?:^|\s)(?:\/?[\w.-]+\/){2,}\S*/g,' ')
    .replace(/\b(?:Bearer\s+\S+|(?:api[_-]?key|token|password|secret)\s*[:=]\s*\S+)/gi,'private value')
    .replace(/^[\s]*[#$>].*$/gm,' ').replace(/[*_#~>|]/g,' ').replace(/\s+/g,' ').trim();
}
export function speechText(value,max=360){
  const clean=cleanSpeech(value),fits=text=>text.length<=max&&Buffer.byteLength(text)<=600;
  if(fits(clean))return clean;
  let complete='';
  for(const {segment} of new Intl.Segmenter('en',{granularity:'sentence'}).segment(clean)){
    const sentence=segment.trim();
    if(!/[.!?。！？]["'’”\])]*$/u.test(sentence))break;
    const candidate=[complete,sentence].filter(Boolean).join(' ');
    if(!fits(candidate))break;
    complete=candidate;
  }
  const withNotice=[complete,MORE].filter(Boolean).join(' ');
  return fits(withNotice)?withNotice:complete||(fits(MORE)?MORE:'');
}
export function spokenSummary(title,body,explicit,context){
  if(context!==undefined)return speechText(spokenText(title,body,explicit,context));
  if(explicit&&cleanSpeech(explicit))return speechText(explicit);
  const heading=cleanSpeech(title),content=cleanSpeech(body);
  const prefix=heading?heading+(/[.!?。！？]$/.test(heading)?'':'.'):'';
  return speechText([prefix,content].filter(Boolean).join(' ')||'Codex has an update.');
}

export function spokenText(title,body,explicit,context){
  if(context!==undefined)return [promptContext(null,context)+'.',cleanSpeech(explicit)||cleanSpeech(body)||'Codex has an update.'].join(' ');
  if(explicit&&cleanSpeech(explicit))return cleanSpeech(explicit);
  const heading=cleanSpeech(title),content=cleanSpeech(body);
  return [heading?heading+(/[.!?。！？]$/.test(heading)?'':'.'):'',content].filter(Boolean).join(' ')||'Codex has an update.';
}

// Prefer a model-written topic label when provided. Otherwise extract a short
// topic from the actual prompt, removing request boilerplate rather than titles.
export function promptContext(prompt,explicit){
  const label=cleanSpeech(explicit).replace(/private value/gi,'').trim();
  if(label)return label.split(/\s+/).slice(0,3).join(' ').replace(/[.!?]+$/,'');
  const filler=new Set('a an the i me my mine we our us you your it its this that these those is are was were be been being have has had do does did can could would should will may might please kindly want wants need needs to of for in on at by with from as and or but so if then just also really about some all any here there now today lets let asked ask asking help thank thanks much make sure like think'.split(' '));
  const words=[...cleanSpeech(prompt).replace(/private value/gi,'').matchAll(/[\p{L}\p{N}]+(?:[’'_\-][\p{L}\p{N}]+)*/gu)].map(m=>m[0]);
  const topics=[];
  for(const word of words){if(!filler.has(word.toLowerCase())&&!topics.some(t=>t.toLowerCase()===word.toLowerCase()))topics.push(word);if(topics.length===3)break;}
  return topics.join(' ')||'Task update';
}
export function turnPrompt(turn){
  const item=turn?.items?.filter(i=>i.type==='userMessage').at(-1);
  return item?.content?.filter(c=>c.type==='text').map(c=>c.text||'').join(' ')||item?.text||item?._pocketRow?.text||'';
}
