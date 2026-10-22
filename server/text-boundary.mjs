/** UTF-16 limits must never leave half of an emoji/supplementary character. */
export function textPrefix(value,limit){
 const text=String(value||'');let end=Math.min(limit,text.length);
 if(end<text.length&&end>0&&/[\uD800-\uDBFF]/.test(text[end-1]))end--;
 return text.slice(0,end);
}
