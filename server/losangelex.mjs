import {readFileSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {TeamAttention} from './losangelex-attention.mjs';

const fail=(status,message)=>Object.assign(Error(message),{status});
const slug=value=>typeof value==='string'&&/^[a-z0-9_-]{1,64}$/.test(value);
const number=value=>/^[1-9][0-9]{0,15}$/.test(value)&&Number.isSafeInteger(Number(value));

// Owner registration is a private local file. Neither devices nor models supply endpoints.
export function loadLosangelex(dir){
  const path=resolve(dir,'losangelex.json');
  if(!existsSync(path))return null;
  try{
    const config=JSON.parse(readFileSync(path,'utf8')),url=new URL(config.url);
    if(url.username||url.password||url.search||url.hash||url.pathname!=='/'||
      !(url.protocol==='https:'||url.protocol==='http:'&&['127.0.0.1','localhost','[::1]'].includes(url.hostname)))throw Error();
    if(typeof config.tokenFile!=='string'||!config.tokenFile.startsWith('/'))throw Error();
    const token=readFileSync(config.tokenFile,'utf8').trim();if(!token||token.length>4096)throw Error();
    return {url:url.origin,token,environmentId:createHash('sha256').update(url.origin+'\0'+token).digest('hex').slice(0,20)};
  }catch{return {invalid:true};} // A broken optional backend must not take Codex offline.
}

export class Losangelex {
  constructor(config,{fetchImpl=fetch}={}){this.config=config;this.fetch=fetchImpl;}
  async call(path,{body,query={}}={}){
    if(!this.config||this.config.invalid)throw fail(503,'Losangelex needs a valid owner registration on this environment.');
    const url=new URL('/hollywood/v2/'+path,this.config.url);
    for(const [key,value] of Object.entries(query))if(value!==undefined&&value!==null)url.searchParams.set(key,String(value));
    let response;
    try{response=await this.fetch(url,{method:body===undefined?'GET':'POST',headers:{Authorization:`Bearer ${this.config.token}`,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),redirect:'manual',signal:AbortSignal.timeout(10000)});}
    catch{throw fail(503,body===undefined?'Losangelex is unavailable. Retry when connected.':'Submission is unconfirmed. Retry the same saved command; do not submit a new copy.');}
    if(response.status>=300&&response.status<400)throw fail(502,'Losangelex endpoint redirected; update its owner registration.');
    // Bound upstream data even on owner-operated endpoints.
    const text=await response.text();if(Buffer.byteLength(text)>8*1024*1024)throw fail(502,'Losangelex response is too large.');
    let value;try{value=JSON.parse(text);}catch{throw fail(502,'Invalid Losangelex response.');}
    if(!response.ok)throw fail([400,404,409,422].includes(response.status)?response.status:503,
      [400,404,409,422].includes(response.status)&&typeof value.error==='string'?value.error.slice(0,250):'Losangelex could not confirm this request. Check connection and owner registration.');
    return value;
  }
  async status(){
    if(!this.config||this.config.invalid)return {id:'losangelex',name:'Losangelex',configured:false,available:false,modes:['team','direct'],permissions:'full',voice:false};
    try{const overview=await this.call('overview');return {id:'losangelex',name:'Losangelex',configured:true,available:true,runtimeReady:!!overview.runtimeReady,environmentId:this.config.environmentId,modes:['team','direct'],permissions:'full',voice:false};}
    catch{return {id:'losangelex',name:'Losangelex',configured:true,available:false,environmentId:this.config.environmentId,modes:['team','direct'],permissions:'full',voice:false};}
  }
}

const queryKeys={teams:['cursor','limit'],overview:[],teammates:['cursor','limit'],history:['task','visibility','before','after','around','limit'],messages:['task','after','limit'],attention:['cursor','limit'],conversations:['task','agent','cursor','limit']};
export function teamRequest(method,tail,query,body){
  const parts=tail.split('/').filter(Boolean);
  if(method==='GET'&&parts.length===1&&['teams','attention'].includes(parts[0]))return {path:parts[0],query:checkedQuery(parts[0],query)};
  if(parts[0]!=='teams'||!slug(parts[1]))throw fail(404,'Unknown Losangelex operation.');
  const base=`teams/${parts[1]}/`,resource=parts[2];
  if(method==='GET'&&parts.length===3&&resource in queryKeys)return {path:base+resource,query:checkedQuery(resource,query)};
  if(method==='GET'&&parts.length===4&&((resource==='messages'&&number(parts[3]))||(resource==='commands'&&/^[a-zA-Z0-9_-]{1,128}$/.test(parts[3]))))return {path:base+resource+'/'+parts[3],query:{}};
  if(method!=='POST')throw fail(404,'Unknown Losangelex operation.');
  let allowed;
  if(parts.length===3&&resource==='messages')allowed=['commandId','task','body','targets','visibility','replyTo'];
  else if(parts.length===3&&resource==='tasks')allowed=['commandId','id','title','body','targets'];
  else if(parts.length===3&&resource==='control')allowed=['commandId','task','agent','action'];
  else if(parts.length===5&&number(parts[3])&&resource==='attention'&&['answer','dismiss'].includes(parts[4]))allowed=parts[4]==='answer'?['commandId','body']:['commandId'];
  else if(parts.length===5&&number(parts[3])&&resource==='approval'&&parts[4]==='answer')allowed=['decision'];
  else throw fail(404,'Unknown Losangelex operation.');
  if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(k=>!allowed.includes(k)))throw fail(400,'Unsupported command fields.');
  if(allowed.includes('commandId')&&(typeof body.commandId!=='string'||!/^[a-zA-Z0-9_-]{1,128}$/.test(body.commandId)))throw fail(400,'Keep a stable command identifier for this submission.');
  return {path:base+parts.slice(2).join('/'),body};
}
function checkedQuery(resource,query){
  if(Object.keys(query).some(k=>!queryKeys[resource].includes(k))||Object.values(query).some(v=>typeof v!=='string'||v.length>2048))throw fail(400,'Unsupported query fields.');
  return query;
}

export function mountLosangelex(app,{dir,codex,client=new Losangelex(loadLosangelex(dir)),db,publish,resolve:resolveAttention}){
  app.get('/api/backends',async(req,res,next)=>{
    try{res.json({backends:[{id:'codex',name:'Codex',configured:true,available:codex.ready,modes:['session','coordinator'],voice:true},await client.status()]});}catch(e){next(e);}
  });
  if(db&&publish){
    const attention=new TeamAttention({client,db,publish,resolve:resolveAttention});attention.mount(app);
    const poll=()=>void attention.poll().catch(()=>{});
    const timer=setInterval(poll,10000);timer.unref();poll();
  }
  app.use('/api/losangelex',async(req,res,next)=>{
    try{
      const command=teamRequest(req.method,req.path,req.query,req.body);
      const value=await client.call(command.path,command);
      res.status(req.method==='POST'?202:200).json(value);
    }catch(e){next(e);}
  });
  return client;
}
