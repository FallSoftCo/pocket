export function speechText(value,max=180){
  const clean=String(value||'').replace(/```[\s\S]*?(?:```|$)/g,' ')
    .replace(/`[^`]*`/g,' ').replace(/\[([^\]]+)\]\([^)]*\)/g,'$1')
    .replace(/https?:\/\/\S+|(?:^|\s)(?:\/?[\w.-]+\/){2,}\S*/g,' ')
    .replace(/\b(?:Bearer\s+\S+|(?:api[_-]?key|token|password|secret)\s*[:=]\s*\S+)/gi,'private value')
    .replace(/^[\s]*[#$>].*$/gm,' ').replace(/[*_#~>|]/g,' ').replace(/\s+/g,' ').trim();
  const words=clean.split(' ').slice(0,28).join(' ');
  if(words.length<=max)return words;
  return words.slice(0,max+1).replace(/\s+\S*$/,'').replace(/[,:; -]+$/,'')+'.';
}
export function spokenSummary(title,body,explicit){
  if(explicit&&speechText(explicit))return speechText(explicit);
  const heading=speechText(title,60),content=speechText(body);
  return speechText(content?`${heading}. ${content}`:heading||'Codex has an update.');
}
