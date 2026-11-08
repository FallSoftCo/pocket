/** Bounded immutable atlas transfer. Keep low-priority background responses small
 * enough that an abandoned page cannot queue a whole atlas ahead of navigation. */
async function readExact(response,expected){
 const length=response.headers.get('content-length');
 if(length!==null&&Number(length)!==expected){await response.body?.cancel();throw Error('Invalid atlas body length');}
 if(!response.body)throw Error('Missing atlas body');
 const reader=response.body.getReader(),bytes=new Uint8Array(expected);let offset=0;
 try{while(true){const {done,value}=await reader.read();if(done)break;
  if(offset+value.byteLength>expected){await reader.cancel();throw Error('Atlas body exceeds transfer budget');}
  bytes.set(value,offset);offset+=value.byteLength;
 }}finally{reader.releaseLock();}
 if(offset!==expected)throw Error('Truncated atlas body');
 return bytes.buffer;
}
export async function downloadAtlas(url,{fetcher=fetch,chunkBytes=32768,maxBytes=2*1024*1024,signal,stallMs=10000,deadlineMs=60000}={}){
 if(!Number.isSafeInteger(chunkBytes)||chunkBytes<1024||!Number.isSafeInteger(maxBytes)||maxBytes<chunkBytes)throw Error('Invalid atlas transfer budget');
 if(!Number.isSafeInteger(stallMs)||stallMs<1||!Number.isSafeInteger(deadlineMs)||deadlineMs<stallMs)throw Error('Invalid atlas time budget');
 const lifetime=signal?AbortSignal.any([signal,AbortSignal.timeout(deadlineMs)]):AbortSignal.timeout(deadlineMs);
 const chunks=[];let offset=0,total=null,etag=null,modified=null;
 while(total===null||offset<total){
  const headers={Range:'bytes='+offset+'-'+(offset+chunkBytes-1)};
  if(modified)headers['If-Range']=modified;
  const response=await fetcher(url,{headers,priority:'low',signal:AbortSignal.any([lifetime,AbortSignal.timeout(stallMs)])});
  const reject=async message=>{await response.body?.cancel().catch(()=>{});throw Error(message);};
  if(response.status===200){
   const size=Number(response.headers.get('content-length'));
   if(offset!==0||!Number.isSafeInteger(size)||size<1||size>chunkBytes)return reject('Atlas server did not preserve bounded range delivery');
   const bytes=await readExact(response,size);
   return new Blob([bytes],{type:'image/webp'});
  }
  if(response.status!==206)return reject('Symbol page unavailable');
  const range=/^bytes (\d+)-(\d+)\/(\d+)$/.exec(response.headers.get('content-range')||'');
  if(!range)return reject('Invalid atlas range');
  const [start,end,length]=range.slice(1).map(Number);
  if(![start,end,length].every(Number.isSafeInteger)||start!==offset||end<start||end-start+1>chunkBytes||length<1||length>maxBytes||end>=length||end!==Math.min(offset+chunkBytes,length)-1||(total!==null&&total!==length))return reject('Atlas range exceeds immutable transfer budget');
  const currentTag=response.headers.get('etag'),currentModified=response.headers.get('last-modified');
  if(!currentTag||(etag!==null&&currentTag!==etag)||(modified!==null&&currentModified!==modified))return reject('Atlas representation changed during transfer');
  etag=currentTag;modified=currentModified;total=length;
  const bytes=await readExact(response,end-start+1);
  chunks.push(bytes);offset=end+1;
 }
 return new Blob(chunks,{type:'image/webp'});
}
