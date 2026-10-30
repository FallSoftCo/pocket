import {DatabaseSync} from 'node:sqlite';
import {WebFleet} from './web-fleet.mjs';
import {WebEnvironments,environmentRoute,qualifyPayload,CatalogPresentation} from './web-environments.mjs';
import express from 'express';
import {gzip} from 'node:zlib';
import {createTailnetBrowserAuth} from './tailnet-browser-auth.mjs';
import http from 'node:http';
import {createHash,createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdirSync,readFileSync,writeFileSync,chmodSync,existsSync} from 'node:fs';
import {homedir} from 'node:os';
import {readFile,stat} from 'node:fs/promises';
import {promisify} from 'node:util';
import {ThreadHistory} from './history.mjs';
import {timelinePage} from './timeline.mjs';
import {WebSocketServer,WebSocket} from 'ws';
import {Codex} from './codex.mjs';
import {sessionIdentity,workTime} from '../web/model.mjs';

// Separate loopback gateway: backends retain task admission and durable receipts.
// Paired source credentials stay server-side; no arbitrary RPC/admin proxy.
export function createWebClient({backend='http://127.0.0.1:18880',origin,secure=true,metadata=null,key=randomBytes(32),basePath='/',migrationSourceOrigin=null,migrationTargetOrigin=null,tailnetAuth=null,environments=null,catalogMetadata=null,fleet=null}={}) {
 if(fleet){environments??=new WebEnvironments();environments.fleet=fleet;}
 const presentation=new CatalogPresentation();
 const target=new URL(backend);if(!['127.0.0.1','localhost','[::1]'].includes(target.hostname))throw Error('Web backend must be loopback.');
 if(!origin||new URL(origin).origin!==origin||(secure&&!origin.startsWith('https://')))throw Error('Set WEB_ORIGIN to the private HTTPS origin.');
 if(!/^\/(?:[a-zA-Z0-9_-]+\/)*$/.test(basePath))throw Error('WEB_BASE_PATH must be a rooted directory path ending in /.');
 for(const value of [migrationSourceOrigin,migrationTargetOrigin])if(value&&(new URL(value).origin!==value||!value.startsWith('https://')||new URL(value).hostname!==new URL(origin).hostname))throw Error('Migration origins must be HTTPS origins on the same private host.');
 const app=express(),server=http.createServer(app),sockets=new WebSocketServer({noServer:true,maxPayload:16384});
 const cookieName=secure?'__Host-nextcomp':'nextcomp-test';
 const sign=token=>createHmac('sha256',key).update(token).digest('base64url');
 const cookie=req=>{const value=(req.headers.cookie||'').split(';').map(x=>x.trim()).find(x=>x.startsWith(cookieName+'='))?.slice(cookieName.length+1)||'';const [token,mac]=value.split('.');if(!token||!/^[a-f0-9]{64}$/.test(token)||!mac)return null;const expected=sign(token);return mac.length===expected.length&&timingSafeEqual(Buffer.from(mac),Buffer.from(expected))?token:null;};
 const setCookie=(res,token)=>res.setHeader('Set-Cookie',`${cookieName}=${token?token+'.'+sign(token):''}; Path=/; HttpOnly; SameSite=Strict; ${secure?'Secure; ':''}Max-Age=${token?365*86400:0}`);
 app.disable('x-powered-by');app.use((req,res,next)=>{res.set({'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Permissions-Policy':'camera=(), microphone=(self)','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"});next();});
 app.use(['/api/voice/recordings',/^\/api\/environments\/[a-z][a-z0-9-]{1,30}\/voice\/recordings/],express.raw({type:'audio/wav',limit:19200044}));
 app.use(express.json({limit:'1mb'}));
 // JSON is the critical representation; binary audio/APKs and atlases pass through unchanged.
 const sendBytes=(req,res,bytes)=>{
  res.vary('Accept-Encoding');
  if(bytes.length<1024||req.acceptsEncodings('gzip','identity')!=='gzip')return res.send(bytes);
  gzip(bytes,{level:4},(error,encoded)=>{if(res.destroyed)return;if(error)return res.send(bytes);res.set('Content-Encoding','gzip').send(encoded);});
 };
 const sendJson=(req,res,data)=>{res.type('json');return sendBytes(req,res,Buffer.from(JSON.stringify(data)));};
 const csrf=(req,res,next)=>req.headers.origin===origin?next():res.status(403).json({error:'Open NextComp at its configured private address.'});
 const forward=async(req,path,body,token)=>fetch(new URL(path,target),{method:req.method,redirect:'error',headers:{...(token?{Authorization:`Bearer ${token}`}:{ }),'Accept-Encoding':'identity','Content-Type':Buffer.isBuffer(body)?'audio/wav':'application/json',...(typeof req.headers?.['x-hollywood-binding']==='string'?{'X-Hollywood-Binding':req.headers['x-hollywood-binding']}:{})},...(body===undefined?{}:{body:Buffer.isBuffer(body)?body:JSON.stringify(body)}),signal:AbortSignal.timeout(45000)});
 app.post('/web/pair',csrf,async(req,res,next)=>{try{const r=await forward(req,'/api/pair',{code:req.body.code,name:String(req.body.name||'NextComp browser').slice(0,100)});const data=await r.json();if(r.ok){setCookie(res,data.token);res.json({id:data.id,host:data.host});}else res.status(r.status).json({error:data.error});}catch(e){next(e);}});
 app.post('/web/tailnet-login',csrf,async(req,res)=>{
  if(!tailnetAuth)return res.status(403).json({error:'Automatic tailnet sign-in is not enabled.'});
  if(cookie(req))return res.json({ok:true});
  try{const device=await tailnetAuth(req);setCookie(res,device.token);res.json({id:device.id,host:device.host});}
  catch(e){res.status(403).json({error:e.message});}
 });
 app.get('/web/config',(_req,res)=>res.json({basePath,migrationSourceOrigin,migrationTargetOrigin}));
 app.post('/web/logout',csrf,(req,res)=>{setCookie(res,null);res.json({ok:true});});
 app.use('/api',async(req,res,next)=>{
  const token=cookie(req);if(!token)return res.status(401).json({error:'Pair this browser to continue.'});
  if(req.method!=='GET'&&req.headers.origin!==origin)return res.status(403).json({error:'Request origin does not match NextComp.'});
  // Browser devices never receive or proxy owner tokens, pairing or arbitrary RPC.
  if(/^\/api\/(?:pair(?:ing)?|devices|notify)(?:\/|$)/.test(req.originalUrl.split('?')[0]))return res.sendStatus(403);
  try{
   const remote=environmentRoute(req.originalUrl);
   if(remote){
    if(!environments)return res.status(403).json({error:'This environment is not connected.'});
    const auth=await forward({...req,method:'GET'},'/api/status',undefined,token);
    if(!auth.ok)return res.status(auth.status).json({error:'Original browser authorization is unavailable.'});
    const identity=await auth.json();await environments.ensure(identity.deviceId,token);
    const response=await environments.forward(identity.deviceId,remote.environmentId,remote.path,req.method,req.method==='GET'?undefined:req.body,
     typeof req.headers['x-hollywood-binding']==='string'?{'X-Hollywood-Binding':req.headers['x-hollywood-binding']}:{ });
    if(response.status===401){await response.arrayBuffer();return res.status(409).json({error:'This environment credential is unavailable. The original browser pairing is unchanged.',environmentId:remote.environmentId});}
    res.status(response.status);
    if(/^\/api\/files\//.test(remote.path)||!response.headers.get('content-type')?.includes('application/json')){res.type(response.headers.get('content-type')||'application/octet-stream');return res.send(Buffer.from(await response.arrayBuffer()));}
    return sendJson(req,res,qualifyPayload(await response.json(),remote.environmentId));
   }
   const guarded=/^\/api\/threads\/([^/]+)\/(reply|queue\/resume|replies\/[^/]+)$/.exec(req.originalUrl.split('?')[0]);
   const detail=req.method==='GET'&&/^\/threads\/[^/]+$/.test(req.path);
   const voiceTarget=req.originalUrl==='/api/voice/start'&&req.body.threadId;
   if(metadata&&(guarded||detail||voiceTarget)){
    const auth=await forward({...req,method:'GET'},'/api/status',undefined,token);
    if(!auth.ok){if(auth.status===401)setCookie(res,null);res.status(auth.status).json({error:'Pair this browser to continue.'});return;}
   }
   if((guarded||(req.originalUrl==='/api/voice/start'&&req.body.threadId))&&metadata){const thread=await metadata.read(guarded?.[1]||req.body.threadId);if(!sessionIdentity(thread).canAcceptDirectInput)return res.status(409).json({error:'Open the parent task to guide this delegated agent.'});}
   let runtimeThread;
   if(detail&&metadata){runtimeThread=await metadata.read(req.path.split('/').at(-1));if(sessionIdentity(runtimeThread).isChild&&!sessionIdentity(runtimeThread).canAcceptDirectInput&&metadata.history){
    res.json(await metadata.history(runtimeThread,req.query.before||null));return;
   }}
   const r=await forward(req,req.originalUrl,req.method==='GET'?undefined:req.body,token);
   if(r.status===401)setCookie(res,null);
   if(!r.ok||!r.headers.get('content-type')?.includes('application/json')){res.status(r.status);res.set('Content-Type',r.headers.get('content-type')||'application/octet-stream');res.send(Buffer.from(await r.arrayBuffer()));return;}
   const data=await r.json();
   if(metadata&&req.method==='GET'&&req.path==='/threads'){
    try{const meta=await metadata.list(req.query.archived==='true'),known=new Map((data.threads||[]).map(t=>[t.id,t]));for(const t of meta.threads){const old=known.get(t.id);if(old)known.set(t.id,{...old,...t,name:old.name,preview:old.preview,recencyAt:workTime({...t,activityAt:old.activityAt})});}
     for(let pass=0;pass<4;pass++)for(const t of meta.threads)if(!known.has(t.id)&&sessionIdentity(t).parentThreadId&&known.has(sessionIdentity(t).parentThreadId))known.set(t.id,{...t,name:t.name||t.agentNickname||'Delegated agent',preview:t.preview||''});
     data.threads=[...known.values()];data.catalogPartial=meta.partial;
    }catch{data.catalogPartial=true;data.catalogWarning='Runtime metadata unavailable; child routing is checked when opening work.';}
   }
   if(metadata&&req.method==='GET'&&/^\/threads\/[^/]+$/.test(req.path)&&data.thread)Object.assign(data.thread,runtimeThread||await metadata.read(data.thread.id));
   if((environments||catalogMetadata)&&req.method==='GET'&&req.path==='/threads'){
    await presentation.annotate(data.threads||[],{key:token,metadata:catalogMetadata?.read?.bind(catalogMetadata),read:async id=>{
     const observed=await forward({...req,method:'GET'},'/api/threads/'+encodeURIComponent(id)+'/native-timeline?observeOnly=1',undefined,token);
     if(!observed.ok)throw Error('Observation unavailable');return observed.json();
    }});
   }
   if(environments&&req.method==='GET'&&req.path==='/threads'){
    const auth=await forward({...req,method:'GET'},'/api/status',undefined,token);if(!auth.ok)return res.status(auth.status).json({error:'Original browser authorization is unavailable.'});
    const identity=await auth.json();let remote;try{await environments.ensure(identity.deviceId,token);remote=await environments.catalog(identity.deviceId,req.originalUrl);}catch{remote={threads:[],environments:[{id:'remote',name:'Other environments',available:false}]};}
    data.threads=[...(data.threads||[]).map(t=>({...t,environmentId:'current',environmentName:'Workstation',environmentUnavailable:false})),...remote.threads];data.environments=[{id:'current',name:'Workstation',available:true},...remote.environments];data.catalogPartial=!!data.nextCursor||remote.environments.some(e=>e.partial);
   }
   if(environments&&req.method==='GET'&&req.path==='/attention'&&Array.isArray(data.notifications)){
    const auth=await forward({...req,method:'GET'},'/api/status',undefined,token);
    if(auth.ok){const identity=await auth.json();const roster=environments.list(identity.deviceId);let next=0;
     await Promise.all(Array.from({length:Math.min(3,roster.length)},async()=>{while(next<roster.length){const source=roster[next++];try{const reply=await environments.forward(identity.deviceId,source.id,'/api/attention');if(reply.ok){const value=qualifyPayload(await reply.json(),source.id);data.notifications.push(...(value.notifications||[]).map(n=>({...n,environmentName:source.name})));}}catch{}}}));
    }
   }
   sendJson(req,res.status(r.status),data);
  }catch(e){next(e);}
 });
 const assets=resolve(dirname(fileURLToPath(import.meta.url)),'../web');
 app.get('/migration.html',(_req,res)=>{if(!migrationTargetOrigin)return res.sendStatus(404);res.set('Content-Security-Policy',`default-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors ${migrationTargetOrigin}; base-uri 'none'`);res.type('html').send('<!doctype html><meta charset="utf-8"><title>NextComp local profile transfer</title><script type="module" src="/migration-frame.mjs"></script>');});
 app.get('/',(req,res)=>{let html=readFileSync(resolve(assets,'index.html'),'utf8').replace(/(src|href)="\/([^"]+)"/g,(_match,attribute,path)=>`${attribute}="${basePath}${path}"`);if(migrationSourceOrigin)res.set('Content-Security-Policy',res.get('Content-Security-Policy')+`; frame-src ${migrationSourceOrigin}`);if(migrationTargetOrigin)html=html.replace('</body>',`<p class="access-link"><a href="${migrationTargetOrigin}/nextcomp/">Open NextComp at its cleaner address ↗</a></p></body>`);res.type('html');sendBytes(req,res,Buffer.from(html));});
 app.get('/manifest.webmanifest',(_req,res)=>{const manifest=JSON.parse(readFileSync(resolve(assets,'manifest.webmanifest'),'utf8'));Object.assign(manifest,{id:basePath,start_url:basePath,scope:basePath});manifest.icons=manifest.icons.map(icon=>({...icon,src:basePath+icon.src.replace(/^\//,'')}));res.type('application/manifest+json').json(manifest);});
 // Immutable release assets: coalesce encoding once per file, retain private
 // conditional caching, and leave binary/range delivery to express.static.
 const textAssets=new Map(),encode=promisify(gzip);
 app.get(/^\/[a-zA-Z0-9][a-zA-Z0-9_.-]*\.(?:mjs|js|css)$/,async(req,res,next)=>{
  try{
   const name=req.path.slice(1);let pending=textAssets.get(name);
   if(!pending){
    pending=(async()=>{
     const file=resolve(assets,name),info=await stat(file);
     if(!info.isFile()||info.size>512*1024)return null;
     const raw=await readFile(file);
     return {raw,gzip:raw.length>=1024?await encode(raw,{level:4}):null,etag:'W/"'+createHash('sha256').update(raw).digest('hex')+'"'};
    })();
    if(textAssets.size>=32)textAssets.delete(textAssets.keys().next().value);
    textAssets.set(name,pending);
    pending.catch(()=>{if(textAssets.get(name)===pending)textAssets.delete(name)});
   }
   const value=await pending;if(!value)return next();
   // Preserve byte-range semantics rather than compressing an arbitrary range.
   if(req.headers.range)return next();
   res.set({'Cache-Control':'private, no-cache',ETag:value.etag});res.vary('Accept-Encoding');
   res.type(name.endsWith('.css')?'css':'js');
   if(value.gzip&&req.acceptsEncodings('gzip','identity')==='gzip'){res.set('Content-Encoding','gzip');return res.send(value.gzip);}
   return res.send(value.raw);
  }catch(error){if(error.code==='ENOENT'||error.code==='ENOTDIR')return next();next(error);}
 });
 app.use(express.static(assets,{etag:true,index:false,setHeaders:res=>res.setHeader('Cache-Control','private, no-cache')}));
 server.on('upgrade',(req,socket,head)=>{
  const token=cookie(req);if(req.url!=='/events'||req.headers.origin!==origin||!token){socket.end('HTTP/1.1 401 Unauthorized\r\n\r\n');return;}
  const upstream=new WebSocket(new URL('/events',backend.replace(/^http/,'ws')),{headers:{Authorization:`Bearer ${token}`}});socket.on('close',()=>upstream.terminate());upstream.on('error',()=>socket.destroy());upstream.on('unexpected-response',(_req,r)=>{r.resume();socket.destroy();upstream.terminate();});
  upstream.once('open',()=>sockets.handleUpgrade(req,socket,head,ws=>{
   const remotes=new Set(),retryTimers=new Set(),attachedSources=new Set();let ended=false,detachFleet=()=>{};
   ws.on('close',()=>{ended=true;detachFleet();for(const timer of retryTimers)clearTimeout(timer);for(const remote of remotes)remote.close();});
   if(environments)void(async()=>{
    const auth=await forward({method:'GET',headers:{}},'/api/status',undefined,token);if(!auth.ok)return;
    const identity=await auth.json();await environments.ensure(identity.deviceId,token);
    const attachSources=()=>{for(const source of environments.list(identity.deviceId)){
     if(source.retired||attachedSources.has(source.id))continue;
     attachedSources.add(source.id);
     let attempts=0;
     const connect=async()=>{
      if(ended)return;
      try{
       const check=await environments.forward(identity.deviceId,source.id,'/api/status');if(!check.ok)return;
       if(ended)return;
       const remote=new WebSocket(new URL('/events',source.base.replace(/^http/,'ws')),{headers:{Authorization:'Bearer '+source.token}});remotes.add(remote);
       remote.on('open',()=>{attempts=0;if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'environmentStatus',environmentId:source.id,available:true}));});
       remote.on('message',raw=>{try{const message=JSON.parse(raw);if(['status','rateLimits','immersion'].includes(message.type))return;if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({...qualifyPayload(message,source.id),environmentId:source.id}));}catch{}});
       remote.on('error',()=>{});
       remote.on('close',()=>{remotes.delete(remote);if(!ended){if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'environmentStatus',environmentId:source.id,available:false}));schedule();}});
      }catch{if(!ended)schedule();}
     };
     const schedule=()=>{const timer=setTimeout(()=>{retryTimers.delete(timer);void connect();},Math.min(30000,1000*2**Math.min(attempts++,5)));retryTimers.add(timer);};
     void connect();
    }};
    attachSources();
    detachFleet=environments.fleet?.onSources(identity.deviceId,()=>{
     if(ended)return;attachSources();
     if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'environmentCatalogChanged'}));
    })||(()=>{});
    if(ended)detachFleet();
   })().catch(()=>{});
   upstream.on('message',(data,isBinary)=>{if(ws.readyState===WebSocket.OPEN)ws.send(data,{binary:isBinary});});ws.on('message',data=>{if(upstream.readyState===WebSocket.OPEN)upstream.send(data);});ws.on('close',()=>upstream.close());upstream.on('close',()=>ws.close());ws.on('error',()=>upstream.close());}));
  // Buffer initial backend status before downstream upgrade completes.
 });
 app.use((err,req,res,_next)=>{res.status(Number.isInteger(err.status)&&err.status>=400&&err.status<=599?err.status:502).json({error:err.message||'Backend unavailable. Reconnect to check delivery.'});});
 return {app,server,sockets};
}
export function runtimeMetadata(codex=new Codex(),{catalogStore=null}={}){
 let catalog;
 let snapshot=new Map(),snapshotPartial=false,cacheAt=0,loading;const history=new ThreadHistory(codex);
 return {async history(thread,before){const raw=await history.read(thread,{before,preferPaging:true});const page=timelinePage(raw,[],{before:raw._pocketPage?null:before});if(raw._pocketPage)Object.assign(page,raw._pocketPage);return {thread:{...thread,turns:[]},timeline:page,pending:[],outgoing:[],notifications:[],notes:[],revision:0,watched:false,historyReadOnly:true};},async list(archived=false){if(!archived&&Date.now()-cacheAt<10000)return {threads:[...snapshot.values()],partial:snapshotPartial};if(loading&&!archived)return loading;
  const work=(async()=>{await codex.connect();const found=new Map();let cursor=null,partial=false;
   for(let i=0;i<6;i++){let result;const args={limit:100,sortKey:'recency_at',sortDirection:'desc',archived,useStateDbOnly:true,...(cursor?{cursor}:{})};try{result=await codex.call('thread/list',args);}catch(e){if(e.rpc?.code!==-32602)throw e;result=await codex.call('thread/list',{...args,sortKey:'created_at'});}
    for(const t of result.data||[])found.set(t.id,{...t,...sessionIdentity(t),recencyAt:workTime(t)});cursor=result.nextCursor;if(!cursor)break;partial=true;
   }if(!archived){snapshot=found;snapshotPartial=!!cursor;cacheAt=Date.now();}return {threads:[...found.values()],partial:!!cursor};})();if(!archived){loading=work;void work.finally(()=>{loading=null;}).catch(()=>{});}return work;
 },async read(id){
  await codex.connect();
  try{const {thread}=await codex.call('thread/read',{threadId:id,includeTurns:false});if(thread?.id!==id)throw Error('Session identity changed.');return {...thread,...sessionIdentity(thread),recencyAt:workTime(thread)};}
  catch(error){
   // Immutable temporary-session declarations survive an unloaded runtime.
   // Read only the existing canonical catalog metadata, never message history.
   if(catalogStore&&error.rpc?.code===-32600&&/^thread not loaded:/.test(error.message)){
    try{
     if(!catalog){catalog=new DatabaseSync(catalogStore,{readOnly:true});catalog.exec('PRAGMA query_only=ON;PRAGMA busy_timeout=25');}
     const row=catalog.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(id),known=row?JSON.parse(row.metadata):null;
     if(known?.id===id&&known.ephemeral===true)return {id,ephemeral:true};
    }catch{/* A missing/changed store is not evidence of an empty session. */}
   }
   throw error;
  }
 }};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const folder=process.env.WEB_DATA||resolve(homedir(),'.local/share/nextcomp-web');mkdirSync(folder,{recursive:true,mode:0o700});const keyPath=resolve(folder,'cookie-key');if(!existsSync(keyPath))writeFileSync(keyPath,randomBytes(32),{mode:0o600,flag:'wx'});chmodSync(keyPath,0o600);
 const tailnetAuth=process.env.WEB_TAILNET_AUTH_SECRETS?createTailnetBrowserAuth({backend:process.env.POCKET_URL||'http://127.0.0.1:18880',ownerToken:JSON.parse(readFileSync(process.env.WEB_TAILNET_AUTH_SECRETS,'utf8')).adminToken}):null;
 const environments=process.env.WEB_ENVIRONMENTS_FILE?WebEnvironments.fromFile(process.env.WEB_ENVIRONMENTS_FILE):null;
 const fleet=new WebFleet({directory:resolve(folder,'fleet'),backend:process.env.POCKET_URL||'http://127.0.0.1:18880'});
 const {server}=createWebClient({environments,fleet,catalogMetadata:process.env.WEB_CATALOG_METADATA==='1'?runtimeMetadata(undefined,{catalogStore:process.env.WEB_CATALOG_STORE||null}):null,tailnetAuth,origin:process.env.WEB_ORIGIN,basePath:process.env.WEB_BASE_PATH||'/',migrationSourceOrigin:process.env.WEB_MIGRATION_SOURCE_ORIGIN||null,migrationTargetOrigin:process.env.WEB_MIGRATION_TARGET_ORIGIN||null,backend:process.env.POCKET_URL,metadata:process.env.WEB_LEGACY_METADATA==='1'?runtimeMetadata():null,key:readFileSync(keyPath)});server.listen(Number(process.env.WEB_PORT||18882),'127.0.0.1',()=>console.log('NextComp web gateway ready on loopback'));
}
