export function protectedImmersionRanges(source){
 const ranges=[];
 // Protect complete Markdown fences, then inline code, links, paths, flags and model identifiers.
 let fence=null,begin=0,length=0,offset=0;
 for(const line of source.split('\n')){const m=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);if(!fence&&m&&!(m[1][0]==='`'&&m[2].includes('`'))){fence=m[1][0];begin=offset;length=m[1].length;}else if(fence&&m&&m[1][0]===fence&&m[1].length>=length&&!m[2].trim()){ranges.push({start:begin,end:offset+line.length});fence=null;}offset+=line.length+1;}if(fence)ranges.push({start:begin,end:source.length});
 for(const pattern of [/(`{3,}|~{3,})[\s\S]*?\1/g,/`[^`\n]+`/g,/https?:\/\/[^\s<>]+/g,/(?:\/[\w.@-]+){2,}(?:\/)?/g,/\b[\w@.-]+\.(?:md|mjs|js|ts|tsx|kt|json|py|sh|hiplc|usd|png|exr|apk)\b/g,/\b(?:gpt-\d[\w.-]*|codex-[\w.-]+|o[134](?:-mini)?)\b/gi,/--[\w-]+(?:=[^\s]+)?/g,/(?:[$€£]\s*)?\b\d+(?:[.,:/-]\d+)*(?:\s?%|[hmks])?\b/g])for(const m of source.matchAll(pattern))ranges.push({start:m.index,end:m.index+m[0].length});
 return ranges.sort((a,b)=>a.start-b.start||a.end-b.end);
}

