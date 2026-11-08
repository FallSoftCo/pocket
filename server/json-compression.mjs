import compression from 'compression';

/** Compress only complete JSON representations. Binary ranges, event streams,
 * uploads and WebSocket traffic keep their original framing and lifecycle. */
export function nativeJsonCompression(){
 return compression({threshold:1024,level:4,filter(req,res){
  if(req.headers.range||res.hasHeader('Content-Range'))return false;
  const type=String(res.getHeader('Content-Type')||'');
  return /^application\/(?:[\w.+-]+\+)?json(?:\s*;|$)/i.test(type)&&compression.filter(req,res);
 }});
}
