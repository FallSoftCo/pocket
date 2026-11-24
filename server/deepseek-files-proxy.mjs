import http from 'node:http';
import https from 'node:https';
import {createHash, randomUUID} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {chmodSync, mkdirSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {homedir} from 'node:os';

const MIB=1024*1024;
const MAX_UPLOAD_BYTES=64*MIB;
const DEFAULT_MAX_REQUEST_BYTES=256*MIB;
const FILE_ID=/^file-api-[A-Za-z0-9_-]+$/;
const HOP_BY_HOP=new Set(['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','transfer-encoding','upgrade']);
const IMAGE_TYPES={
  'image/png':{extension:'png'},
  'image/jpeg':{extension:'jpg'},
  'image/gif':{extension:'gif'},
  'image/webp':{extension:'webp'}
};

const failure=(message,status=400,code='DEEPSEEK_FILES_PROXY_ERROR')=>Object.assign(Error(message),{status,code});
const integer=(value,fallback,{min=1,max=Number.MAX_SAFE_INTEGER}={})=>{const parsed=Number(value);return Number.isSafeInteger(parsed)&&parsed>=min&&parsed<=max?parsed:fallback;};

export function sniffImage(bytes){
  if(!Buffer.isBuffer(bytes))return null;
  if(bytes.length>=8&&bytes.subarray(0,8).equals(Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a])))return 'image/png';
  if(bytes.length>=3&&bytes[0]===0xff&&bytes[1]===0xd8&&bytes[2]===0xff)return 'image/jpeg';
  if(bytes.length>=6){const head=bytes.subarray(0,6).toString('ascii');if(head==='GIF87a'||head==='GIF89a')return 'image/gif';}
  if(bytes.length>=12&&bytes.subarray(0,4).toString('ascii')==='RIFF'&&bytes.subarray(8,12).toString('ascii')==='WEBP')return 'image/webp';
  return null;
}

export function decodeImageDataUrl(value,{maxBytes=MAX_UPLOAD_BYTES}={}){
  if(typeof value!=='string'||!value.startsWith('data:'))throw failure('The image content is not a data URL.');
  const comma=value.indexOf(',');
  if(comma<0)throw failure('The image data URL is malformed.');
  const metadata=value.slice(5,comma);
  if(!/(?:^|;)base64(?:;|$)/i.test(metadata))throw failure('Only base64 image data URLs can be offloaded to the DeepSeek Files API.');
  const encoded=value.slice(comma+1).replace(/\s+/g,'');
  if(encoded.length>Math.ceil(maxBytes*4/3)+8)throw failure(`An inline image exceeds the ${MAX_UPLOAD_BYTES/MIB} MiB Files API image limit.`,413,'DEEPSEEK_IMAGE_TOO_LARGE');
  const normalized=encoded.replace(/-/g,'+').replace(/_/g,'/');
  const bytes=Buffer.from(normalized,'base64');
  if(!bytes.length)throw failure('The image data URL contains no image bytes.');
  if(bytes.length>maxBytes)throw failure(`An inline image exceeds the ${MAX_UPLOAD_BYTES/MIB} MiB Files API image limit.`,413,'DEEPSEEK_IMAGE_TOO_LARGE');
  const mime=sniffImage(bytes);
  if(!mime||!IMAGE_TYPES[mime])throw failure('DeepSeek accepts only JPEG, PNG, GIF or WebP image data.',415,'DEEPSEEK_IMAGE_FORMAT_UNSUPPORTED');
  return {bytes,mime,extension:IMAGE_TYPES[mime].extension,length:bytes.length};
}

export class FileIdStore{
  constructor(path){
    if(typeof path!=='string'||!path)throw Error('A durable Files API cache path is required.');
    mkdirSync(dirname(path),{recursive:true,mode:0o700});
    this.path=path;this.db=new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL;PRAGMA synchronous=NORMAL;PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS deepseek_files(
        credential_scope TEXT NOT NULL,
        content_sha256 TEXT NOT NULL,
        file_id TEXT NOT NULL,
        mime TEXT NOT NULL,
        bytes INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        last_used_at INTEGER NOT NULL,
        PRIMARY KEY(credential_scope,content_sha256)
      )`);
    try{chmodSync(path,0o600);}catch{}
  }
  lookup(scope,sha256){
    const row=this.db.prepare('SELECT file_id,mime,bytes FROM deepseek_files WHERE credential_scope=? AND content_sha256=?').get(scope,sha256);
    if(!row)return null;
    this.db.prepare('UPDATE deepseek_files SET last_used_at=? WHERE credential_scope=? AND content_sha256=?').run(Date.now(),scope,sha256);
    return row;
  }
  save({scope,sha256,fileId,mime,bytes,clock=Date.now}){
    const now=clock();
    this.db.prepare(`INSERT INTO deepseek_files(credential_scope,content_sha256,file_id,mime,bytes,created_at,last_used_at)
      VALUES(?,?,?,?,?,?,?) ON CONFLICT(credential_scope,content_sha256) DO UPDATE SET
      file_id=excluded.file_id,mime=excluded.mime,bytes=excluded.bytes,last_used_at=excluded.last_used_at`)
      .run(scope,sha256,fileId,mime,bytes,now,now);
  }
  count(){return this.db.prepare('SELECT count(*) AS count FROM deepseek_files').get().count;}
  close(){this.db.close();}
}

export class FilesApiClient{
  constructor({upstreamBase,fetchImpl=globalThis.fetch,timeoutMs=600000}={}){
    let base;try{base=new URL(upstreamBase);}catch{throw Error('Invalid DeepSeek upstream URL.');}
    if(!['http:','https:'].includes(base.protocol)||base.username||base.password)throw Error('Invalid DeepSeek upstream URL.');
    this.upstreamBase=base;this.fetchImpl=fetchImpl;this.timeoutMs=timeoutMs;
  }
  async upload({authorization,bytes,mime,sha256,filename}){
    if(typeof authorization!=='string'||!authorization.trim())throw failure('DeepSeek authorization is required to upload an image.',401,'DEEPSEEK_AUTH_REQUIRED');
    const form=new FormData();
    form.append('purpose','user_data');
    form.append('file',new Blob([bytes],{type:mime}),filename||`image-${sha256.slice(0,16)}.${IMAGE_TYPES[mime].extension}`);
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),this.timeoutMs);
    let response;
    try{
      response=await this.fetchImpl(new URL('/files',this.upstreamBase),{method:'POST',headers:{Authorization:authorization},body:form,redirect:'error',signal:controller.signal});
    }catch(error){
      throw failure(`DeepSeek Files API upload failed: ${error?.name==='AbortError'?'the upload timed out':error.message}`,502,'DEEPSEEK_FILES_UPLOAD_FAILED');
    }finally{clearTimeout(timer);}
    if(!response.ok){
      let message=`DeepSeek Files API returned HTTP ${response.status}.`;
      try{const text=(await response.text()).slice(0,4096),parsed=JSON.parse(text);if(typeof parsed?.error?.message==='string'&&parsed.error.message.trim())message=parsed.error.message.trim().slice(0,1000);}catch{}
      throw failure(message,response.status===413?413:502,'DEEPSEEK_FILES_UPLOAD_FAILED');
    }
    let value;
    try{value=await response.json();}catch{throw failure('DeepSeek Files API returned an invalid response.',502,'DEEPSEEK_FILES_UPLOAD_FAILED');}
    if(typeof value?.id!=='string'||!FILE_ID.test(value.id))throw failure('DeepSeek Files API did not return a usable file ID.',502,'DEEPSEEK_FILES_UPLOAD_FAILED');
    return value.id;
  }
}

const isInlineDataUrl=value=>typeof value==='string'&&value.startsWith('data:');
const isInlineImageDataUrl=value=>isInlineDataUrl(value)&&/^data:image\//i.test(value);

export async function rewriteInlineImages(payload,{resolveImage}={}){
  if(typeof resolveImage!=='function')throw Error('An image resolver is required.');
  const stats={converted:0,bytes:0};
  const convert=async image=>{const fileId=await resolveImage(image);stats.converted++;stats.bytes+=image.length;return fileId;};
  const visit=async value=>{
    if(Array.isArray(value)){for(const item of value)await visit(item);return;}
    if(!value||typeof value!=='object')return;
    if(value.type==='input_image'){
      const url=typeof value.image_url==='string'?value.image_url:value.image_url?.url;
      if(isInlineDataUrl(url)){const image=decodeImageDataUrl(url);value.file_id=await convert(image);delete value.image_url;return;}
    }
    if(value.type==='image_url'){
      const url=typeof value.image_url==='string'?value.image_url:value.image_url?.url;
      if(isInlineDataUrl(url)){const image=decodeImageDataUrl(url);value.type='file';value.file_id=await convert(image);delete value.image_url;return;}
    }
    if(isInlineImageDataUrl(value.file_data)){
      const image=decodeImageDataUrl(value.file_data);value.file_id=await convert(image);delete value.file_data;delete value.filename;return;
    }
    for(const child of Object.values(value))await visit(child);
  };
  await visit(payload);
  return stats;
}

const credentialScope=authorization=>createHash('sha256').update(String(authorization||''),'utf8').digest('hex');
const readBody=async(req,{limit})=>{
  const declared=Number(req.headers['content-length']);
  if(Number.isFinite(declared)&&declared>limit)throw failure(`The request body exceeds the proxy's ${limit/MIB} MiB safety limit.`,413,'DEEPSEEK_PROXY_BODY_TOO_LARGE');
  const chunks=[];let total=0;
  for await(const chunk of req){
    total+=chunk.length;
    if(total>limit)throw failure(`The request body exceeds the proxy's ${limit/MIB} MiB safety limit.`,413,'DEEPSEEK_PROXY_BODY_TOO_LARGE');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks,total);
};

function parsedJsonBody(buffer){
  if(!buffer.length)return null;
  try{return JSON.parse(buffer.toString('utf8'));}catch{return null;}
}

function sendJson(res,status,error,requestId){
  if(res.headersSent)return res.destroy();
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-NextComp-Request-Id':requestId});
  res.end(JSON.stringify({error:{message:error.message,type:error.code||'DEEPSEEK_FILES_PROXY_ERROR'}}));
}

function filteredResponseHeaders(rawHeaders,extra){
  const headers=[];
  for(let index=0;index<rawHeaders.length;index+=2){
    const name=rawHeaders[index],value=rawHeaders[index+1];
    if(!HOP_BY_HOP.has(name.toLowerCase()))headers.push(name,value);
  }
  for(const [name,value]of Object.entries(extra||{}))headers.push(name,value);
  return headers;
}

function forwardRequest({req,res,body,upstreamBase,requestId,converted}){
  return new Promise((resolve,reject)=>{
    let target;
    try{target=new URL(req.url,upstreamBase);}catch(error){reject(error);return;}
    const headers={...req.headers};
    for(const name of HOP_BY_HOP)delete headers[name];
    delete headers.host;
    if(body){
      delete headers['content-length'];
      headers['content-length']=String(body.length);
    }
    const transport=target.protocol==='https:'?https:http;
    const upstream=transport.request(target,{method:req.method,headers},upstreamResponse=>{
      const status=upstreamResponse.statusCode||502;
      if(status===413)console.error(JSON.stringify({event:'deepseek_upstream_413',requestId,converted:converted||0}));
      if(res.destroyed){upstreamResponse.destroy();resolve();return;}
      res.writeHead(status,upstreamResponse.statusMessage,filteredResponseHeaders(upstreamResponse.rawHeaders,{'X-NextComp-Request-Id':requestId}));
      upstreamResponse.on('error',reject);
      upstreamResponse.on('end',resolve);
      upstreamResponse.pipe(res);
    });
    upstream.setTimeout(15*60*1000,()=>upstream.destroy(failure('The DeepSeek upstream request timed out.',504,'DEEPSEEK_UPSTREAM_TIMEOUT')));
    upstream.on('error',reject);
    res.once('close',()=>{if(!res.writableEnded)upstream.destroy();});
    if(body)upstream.end(body);else req.pipe(upstream);
  });
}

export async function startDeepSeekFilesProxy({
  host='127.0.0.1',
  port=18882,
  upstreamBase=process.env.DEEPSEEK_UPSTREAM_BASE||'https://api.deepseek.com/v1',
  dbPath=process.env.DEEPSEEK_FILES_DB||resolve(process.env.POCKET_DATA||resolve(homedir(),'.local/share/nextcomp-deepseek/data'),'deepseek-files.sqlite'),
  maxRequestBytes=DEFAULT_MAX_REQUEST_BYTES,
  fetchImpl=globalThis.fetch,
  clock=Date.now
}={}){
  const store=new FileIdStore(dbPath);
  const files=new FilesApiClient({upstreamBase,fetchImpl});
  const inflight=new Map(),metrics={requests:0,convertedRequests:0,images:0,uploads:0,cacheHits:0};
  const resolveImage=authorization=>async image=>{
    const scope=credentialScope(authorization);
    const sha256=createHash('sha256').update(image.bytes).digest('hex');
    const cached=store.lookup(scope,sha256);
    if(cached){metrics.cacheHits++;return cached.file_id;}
    const key=`${scope}:${sha256}`;
    if(inflight.has(key))return inflight.get(key);
    const pending=(async()=>{
      const fileId=await files.upload({authorization,bytes:image.bytes,mime:image.mime,sha256});
      metrics.uploads++;
      store.save({scope,sha256,fileId,mime:image.mime,bytes:image.length,clock});
      return fileId;
    })();
    inflight.set(key,pending);
    try{return await pending;}finally{inflight.delete(key);}
  };
  const server=http.createServer(async(req,res)=>{
    const requestId=randomUUID();
    metrics.requests++;
    try{
      const pathname=new URL(req.url||'/','http://127.0.0.1').pathname;
      if(pathname==='/healthz'&&(req.method==='GET'||req.method==='HEAD')){
        res.writeHead(200,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
        res.end(JSON.stringify({ok:true,cachedFiles:store.count(),upstreamOrigin:new URL(upstreamBase).origin,...metrics}));
        return;
      }
      const contentType=String(req.headers['content-type']||'').toLowerCase();
      const jsonEndpoint=/\/responses$|\/chat\/completions$/.test(pathname);
      if(req.method==='POST'&&jsonEndpoint&&contentType.includes('application/json')){
        let body=await readBody(req,{limit:maxRequestBytes});
        const payload=parsedJsonBody(body);
        if(payload){
          const stats=await rewriteInlineImages(payload,{resolveImage:resolveImage(req.headers.authorization)});
          if(stats.converted){
            body=Buffer.from(JSON.stringify(payload),'utf8');
            metrics.convertedRequests++;metrics.images+=stats.converted;
            res.setHeader('X-NextComp-Images-Offloaded',String(stats.converted));
            console.error(JSON.stringify({event:'deepseek_images_offloaded',requestId,count:stats.converted,bytes:stats.bytes}));
            await forwardRequest({req,res,body,upstreamBase,requestId,converted:stats.converted});
            return;
          }
        }
        await forwardRequest({req,res,body,upstreamBase,requestId,converted:0});
        return;
      }
      await forwardRequest({req,res,upstreamBase,requestId,converted:0});
    }catch(error){
      if(!res.headersSent)sendJson(res,error.status||502,error,requestId);
      else res.destroy();
    }
  });
  server.requestTimeout=0;
  server.headersTimeout=60000;
  server.keepAliveTimeout=65000;
  await new Promise((resolveListen,rejectListen)=>{server.once('error',rejectListen);server.listen(port,host,()=>{server.off('error',rejectListen);resolveListen();});});
  const address=server.address();
  const actualPort=typeof address==='object'&&address?address.port:port;
  return {
    server,store,port:actualPort,url:`http://${host}:${actualPort}`,
    async close(){
      await new Promise(resolveClose=>server.close(resolveClose));
      store.close();
    }
  };
}

async function main(){
  const proxy=await startDeepSeekFilesProxy({
    host:process.env.DEEPSEEK_FILES_PROXY_HOST||'127.0.0.1',
    port:integer(process.env.DEEPSEEK_FILES_PROXY_PORT,18882,{max:65535}),
    maxRequestBytes:integer(process.env.DEEPSEEK_FILES_PROXY_MAX_REQUEST_BYTES,DEFAULT_MAX_REQUEST_BYTES,{min:1})
  });
  console.error(JSON.stringify({event:'deepseek_files_proxy_ready',url:proxy.url,cachedFiles:proxy.store.count()}));
  let closing=false;
  const shutdown=async()=>{if(closing)return;closing=true;await proxy.close();};
  process.once('SIGTERM',()=>void shutdown().finally(()=>process.exit(0)));
  process.once('SIGINT',()=>void shutdown().finally(()=>process.exit(0)));
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(JSON.stringify({event:'deepseek_files_proxy_failed',message:error.message}));process.exit(1);});
