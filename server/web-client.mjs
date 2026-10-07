import express from 'express';
import http from 'node:http';
import {createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdirSync,readFileSync,writeFileSync,chmodSync,existsSync} from 'node:fs';
import {homedir} from 'node:os';
import {ThreadHistory} from './history.mjs';
import {timelinePage} from './timeline.mjs';
import {WebSocketServer,WebSocket} from 'ws';
import {Codex} from './codex.mjs';
import {sessionIdentity,workTime} from '../web/model.mjs';

// Separate loopback gateway: no owner credentials, database writes or backend restart.
export function createWebClient({backend='http://127.0.0.1:18880',origin,secure=true,metadata=null,key=randomBytes(32)}={}) {
 const target=new URL(backend);if(!['127.0.0.1','localhost','[::1]'].includes(target.hostname))throw Error('Web backend must be loopback.');
 if(!origin||new URL(origin).origin!==origin||(secure&&!origin.startsWith('https://')))throw Error('Set WEB_ORIGIN to the private HTTPS origin.');
 const app=express(),server=http.createServer(app),sockets=new WebSocketServer({noServer:true,maxPayload:16384});
 const cookieName=secure?'__Host-nextcomp':'nextcomp-test';
 const sign=token=>createHmac('sha256',key).update(token).digest('base64url');
 const cookie=req=>{const value=(req.headers.cookie||'').split(';').map(x=>x.trim()).find(x=>x.startsWith(cookieName+'='))?.slice(cookieName.length+1)||'';const [token,mac]=value.split('.');if(!token||!/^[a-f0-9]{64}$/.test(token)||!mac)return null;const expected=sign(token);return mac.length===expected.length&&timingSafeEqual(Buffer.from(mac),Buffer.from(expected))?token:null;};
 const setCookie=(res,token)=>res.setHeader('Set-Cookie',`${cookieName}=${token?token+'.'+sign(token):''}; Path=/; HttpOnly; SameSite=Strict; ${secure?'Secure; ':''}Max-Age=${token?365*86400:0}`);
 app.disable('x-powered-by');app.use((req,res,next)=>{res.set({'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Permissions-Policy':'camera=(), microphone=(self)','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"});next();});
 app.use(express.json({limit:'1mb'}));
 const csrf=(req,res,next)=>req.headers.origin===origin?next():res.status(403).json({error:'Open NextComp at its configured private address.'});
 const forward=async(req,path,body,token)=>fetch(new URL(path,target),{method:req.method,redirect:'error',headers:{...(token?{Authorization:`Bearer ${token}`}:{ }),'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(45000)});
 app.post('/web/pair',csrf,async(req,res,next)=>{try{const r=await forward(req,'/api/pair',{code:req.body.code,name:String(req.body.name||'NextComp browser').slice(0,100)});const data=await r.json();if(r.ok){setCookie(res,data.token);res.json({id:data.id,host:data.host});}else res.status(r.status).json({error:data.error});}catch(e){next(e);}});
 app.post('/web/logout',csrf,(req,res)=>{setCookie(res,null);res.json({ok:true});});
 app.use('/api',async(req,res,next)=>{
  const token=cookie(req);if(!token)return res.status(401).json({error:'Pair this browser to continue.'});
  if(req.method!=='GET'&&req.headers.origin!==origin)return res.status(403).json({error:'Request origin does not match NextComp.'});
  // Browser devices never receive or proxy owner tokens, pairing or arbitrary RPC.
  if(/^\/api\/(?:pair(?:ing)?|devices|notify)(?:\/|$)/.test(req.originalUrl.split('?')[0]))return res.sendStatus(403);
  try{
   const guarded=/^\/api\/threads\/([^/]+)\/(reply|queue\/resume|replies\/[^/]+)$/.exec(req.originalUrl.split('?')[0]);
   if((guarded||(req.originalUrl==='/api/voice/start'&&req.body.threadId))&&metadata){const thread=await metadata.read(guarded?.[1]||req.body.threadId);if(!sessionIdentity(thread).canAcceptDirectInput)return res.status(409).json({error:'Open the parent task to guide this delegated agent.'});}
   const detail=req.method==='GET'&&/^\/threads\/[^/]+$/.test(req.path);let runtimeThread;
   if(detail&&metadata){runtimeThread=await metadata.read(req.path.split('/').at(-1));if(sessionIdentity(runtimeThread).isChild&&!sessionIdentity(runtimeThread).canAcceptDirectInput&&metadata.history){
    const auth=await forward({...req,method:'GET'},'/api/status',undefined,token);if(!auth.ok){res.status(auth.status).json({error:'Pair this browser to continue.'});return;}
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
   res.status(r.status).json(data);
  }catch(e){next(e);}
 });
 const assets=resolve(dirname(fileURLToPath(import.meta.url)),'../web');app.use(express.static(assets,{etag:true}));
 server.on('upgrade',(req,socket,head)=>{
  const token=cookie(req);if(req.url!=='/events'||req.headers.origin!==origin||!token){socket.end('HTTP/1.1 401 Unauthorized\r\n\r\n');return;}
  const upstream=new WebSocket(new URL('/events',backend.replace(/^http/,'ws')),{headers:{Authorization:`Bearer ${token}`}});socket.on('close',()=>upstream.terminate());upstream.on('error',()=>socket.destroy());upstream.on('unexpected-response',(_req,r)=>{r.resume();socket.destroy();upstream.terminate();});
  upstream.once('open',()=>sockets.handleUpgrade(req,socket,head,ws=>{upstream.on('message',(data,isBinary)=>{if(ws.readyState===WebSocket.OPEN)ws.send(data,{binary:isBinary});});ws.on('message',data=>{if(upstream.readyState===WebSocket.OPEN)upstream.send(data);});ws.on('close',()=>upstream.close());upstream.on('close',()=>ws.close());ws.on('error',()=>upstream.close());}));
  // Buffer initial backend status before downstream upgrade completes.
 });
 app.use((err,req,res,_next)=>{res.status(502).json({error:err.message||'Backend unavailable. Reconnect to check delivery.'});});
 return {app,server,sockets};
}
export function runtimeMetadata(codex=new Codex()){
 let snapshot=new Map(),cacheAt=0,loading;const history=new ThreadHistory(codex);
 return {async history(thread,before){const raw=await history.read(thread,{before,preferPaging:true});const page=timelinePage(raw,[],{before:raw._pocketPage?null:before});if(raw._pocketPage)Object.assign(page,raw._pocketPage);return {thread:{...thread,turns:[]},timeline:page,pending:[],outgoing:[],notifications:[],notes:[],revision:0,watched:false,historyReadOnly:true};},async list(archived=false){if(!archived&&Date.now()-cacheAt<10000)return {threads:[...snapshot.values()],partial:false};if(loading&&!archived)return loading;
  const work=(async()=>{await codex.connect();const found=new Map();let cursor=null,partial=false;
   for(let i=0;i<6;i++){let result;const args={limit:100,sortKey:'recency_at',sortDirection:'desc',archived,useStateDbOnly:true,...(cursor?{cursor}:{})};try{result=await codex.call('thread/list',args);}catch(e){if(e.rpc?.code!==-32602)throw e;result=await codex.call('thread/list',{...args,sortKey:'created_at'});}
    for(const t of result.data||[])found.set(t.id,{...t,...sessionIdentity(t),recencyAt:workTime(t)});cursor=result.nextCursor;if(!cursor)break;partial=true;
   }if(!archived){snapshot=found;cacheAt=Date.now();}return {threads:[...found.values()],partial:!!cursor};})();if(!archived){loading=work;void work.finally(()=>{loading=null;}).catch(()=>{});}return work;
 },async read(id){await codex.connect();const {thread}=await codex.call('thread/read',{threadId:id,includeTurns:false});if(thread?.id!==id)throw Error('Session identity changed.');return {...thread,...sessionIdentity(thread),recencyAt:workTime(thread)};}};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const folder=process.env.WEB_DATA||resolve(homedir(),'.local/share/nextcomp-web');mkdirSync(folder,{recursive:true,mode:0o700});const keyPath=resolve(folder,'cookie-key');if(!existsSync(keyPath))writeFileSync(keyPath,randomBytes(32),{mode:0o600,flag:'wx'});chmodSync(keyPath,0o600);
 const {server}=createWebClient({origin:process.env.WEB_ORIGIN,backend:process.env.POCKET_URL,metadata:runtimeMetadata(),key:readFileSync(keyPath)});server.listen(Number(process.env.WEB_PORT||18882),'127.0.0.1',()=>console.log('NextComp web gateway ready on loopback'));
}
