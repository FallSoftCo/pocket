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
export function spokenSummary(title,body,explicit){
  if(explicit&&cleanSpeech(explicit))return speechText(explicit);
  const heading=cleanSpeech(title),content=cleanSpeech(body);
  const prefix=heading?heading+(/[.!?。！？]$/.test(heading)?'':'.'):'';
  return speechText([prefix,content].filter(Boolean).join(' ')||'Codex has an update.');
}

export function spokenText(title,body,explicit){
  if(explicit&&cleanSpeech(explicit))return cleanSpeech(explicit);
  const heading=cleanSpeech(title),content=cleanSpeech(body);
  return [heading?heading+(/[.!?。！？]$/.test(heading)?'':'.'):'',content].filter(Boolean).join(' ')||'Codex has an update.';
}
