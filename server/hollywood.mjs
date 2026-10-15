import {constants,closeSync,existsSync,fstatSync,openSync,readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';

const fail=(status,message,code='HOLLYWOOD_REQUEST_FAILED')=>Object.assign(Error(message),{status,code});
const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const operation=value=>typeof value==='string'&&/^[A-Za-z0-9_:-]{1,128}$/.test(value);
const fields=(value,allowed)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).every(key=>allowed.includes(key));

function protectedFile(path){
  if(typeof path!=='string'||!path.startsWith('/'))throw fail(503,'Hollywood registration needs a protected absolute file.');
  const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try{
    const stat=fstatSync(fd);
    if(!stat.isFile()||(stat.mode&0o077)||stat.size>65536||![0,process.getuid()].includes(stat.uid))throw fail(503,'Hollywood registration file permissions are invalid.');
    return readFileSync(fd,'utf8');
  }finally{closeSync(fd);}
}

/** Installation-owned human bindings. Neither a phone nor an agent supplies URLs or tokens. */
export function loadHollywood(dir){
  const path=resolve(dir,'hollywood.json');
  if(!existsSync(path))return null;
  try{
    const raw=JSON.parse(protectedFile(path));
    if(raw.format!=='hollywood-nextcomp-binding/v1'||!uuid(raw.installationId))throw Error();
    function binding(value){
      if(!fields(value,['url','tokenFile','authorityId','principalId','defaultRoomId'])||
        ![value.authorityId,value.principalId,value.defaultRoomId].every(uuid))throw Error();
      const url=new URL(value.url);
      if(url.username||url.password||url.search||url.hash||url.pathname!=='/'||
        !(url.protocol==='https:'||url.protocol==='http:'&&['127.0.0.1','localhost','[::1]'].includes(url.hostname)))throw Error();
      const token=protectedFile(value.tokenFile).trim();
      if(!/^[A-Za-z0-9_-]{40,128}$/.test(token))throw Error();
      const id=createHash('sha256').update(JSON.stringify([raw.installationId,value.authorityId,value.principalId])).digest('hex');
      return {...value,url:url.origin,token,bindingId:id,installationId:raw.installationId};
    }
    const devices=new Map();
    if(raw.devices!==undefined&&!fields(raw.devices,Object.keys(raw.devices)))throw Error();
    for(const [device,value] of Object.entries(raw.devices||{})){
      if(device!=='owner'&&!uuid(device))throw Error();
      devices.set(device,binding(value));
    }
    return {native:raw.native!==false,installationId:raw.installationId,devices,defaultBinding:raw.default?binding(raw.default):null};
  }catch{return {invalid:true,native:true};}
}

export class HollywoodClient {
  constructor(config,{fetchImpl=fetch}={}){this.config=config;this.fetch=fetchImpl;this.verified=null;}
  envelope(){const c=this.config;return {bindingId:c.bindingId,installationId:c.installationId,authorityId:c.authorityId,principalId:c.principalId,defaultRoomId:c.defaultRoomId};}
  async raw(path,{method='GET',body,query={}}={}){
    const url=new URL('/hollywood/v3/'+path,this.config.url);
    for(const [key,value] of Object.entries(query))url.searchParams.set(key,String(value));
    let response;
    try{response=await this.fetch(url,{method,headers:{Authorization:`Bearer ${this.config.token}`,...(body===undefined?{}:{'Content-Type':'application/json'})},
      ...(body===undefined?{}:{body:JSON.stringify(body)}),redirect:'manual',signal:AbortSignal.timeout(10000)});}
    catch{throw fail(503,body===undefined?'Hollywood is unavailable.':'Submission is unconfirmed. Inspect the original saved receipt before sending again.','HOLLYWOOD_OUTCOME_UNCONFIRMED');}
    if(response.status>=300&&response.status<400)throw fail(502,'Hollywood redirected. The owner must reconcile this installation.','HOLLYWOOD_BINDING_UNCONFIRMED');
    let text;
    try{text=await response.text();}catch{throw fail(502,'Hollywood reply is unconfirmed.','HOLLYWOOD_OUTCOME_UNCONFIRMED');}
    if(Buffer.byteLength(text)>8*1024*1024)throw fail(502,'Hollywood reply exceeds the limit.','HOLLYWOOD_OUTCOME_UNCONFIRMED');
    let value;
    try{value=JSON.parse(text);if(!value||Array.isArray(value)||typeof value!=='object')throw Error();}
    catch{throw fail(502,'Hollywood reply is unconfirmed.','HOLLYWOOD_OUTCOME_UNCONFIRMED');}
    if(!response.ok){
      const rejected=[400,403,404,409,422].includes(response.status);
      throw fail(rejected?response.status:503,rejected&&typeof value.error==='string'?value.error.slice(0,250):'Hollywood could not confirm the original operation.',rejected?'HOLLYWOOD_REQUEST_REJECTED':'HOLLYWOOD_OUTCOME_UNCONFIRMED');
    }
    const authority=value.authorityId||value.scope?.authorityId||value.event?.authority||value.request?.authority;
    if(authority&&authority!==this.config.authorityId)throw fail(409,'Hollywood reply belongs to another enterprise.','HOLLYWOOD_BINDING_MISMATCH');
    return value;
  }
  async verify(){
    if(this.verified)return this.verified;
    const work=(async()=>{
      const c=this.config;
      const principal=await this.raw('principals/'+c.principalId);
      if(principal.id!==c.principalId||principal.authority!==c.authorityId||principal.kind!=='human'||!principal.active)throw fail(403,'This device needs its own active Hollywood human identity.','HOLLYWOOD_BINDING_MISMATCH');
      const catalog=await this.raw('rooms');
      if(catalog.authorityId!==c.authorityId||!catalog.rooms?.some(room=>room.id===c.defaultRoomId))throw fail(403,'The configured default room is not accessible.','HOLLYWOOD_BINDING_MISMATCH');
      return this.envelope();
    })();
    this.verified=work;
    try{return await work;}catch(error){this.verified=null;throw error;}
  }
  async call(path,options){await this.verify();return this.raw(path,options);}
}

export class HollywoodGateway {
  constructor(config,options={}){this.config=config;this.options=options;this.clients=new Map();}
  client(device){
    if(!this.config||this.config.invalid)throw fail(503,'This environment needs a valid Hollywood installation binding.');
    const config=this.config.devices.get(device)||this.config.defaultBinding;
    if(!config)throw fail(403,'This paired device has no Hollywood human binding.');
    if(!this.clients.has(config.bindingId))this.clients.set(config.bindingId,new HollywoodClient(config,this.options));
    return this.clients.get(config.bindingId);
  }
  async status(device){
    try{const client=this.client(device);const identity=await client.verify();return {id:'lax',name:'LAX',configured:true,available:true,api:3,modes:['rooms','direct'],...identity};}
    catch(error){return {id:'lax',name:'LAX',configured:!!this.config&&!this.config.invalid,available:false,api:3,error:error.code||'HOLLYWOOD_UNAVAILABLE'};}
  }
}

/** Human routes only: no arbitrary RPC, connector claims, host enrollment or tokens. */
export function hollywoodRequest(method,tail,query,body){
  const p=tail.split('/').filter(Boolean);
  let allowed=[];
  let uuidCursor=false;
  if(method==='GET'&&p.length===1&&p[0]==='inbox')allowed=['after','limit'];
  else if(method==='GET'&&p.length===2&&p[0]==='inbox'&&p[1]==='pending')allowed=['after','limit'];
  else if(method==='GET'&&p.length===3&&p[0]==='inbox'&&p[1]==='events'&&uuid(p[2]))allowed=[];
  else if(method==='GET'&&p.length===1&&p[0]==='rooms')allowed=[];
  else if(method==='GET'&&p.length===2&&p[0]==='rooms'&&uuid(p[1]))allowed=[];
  else if(method==='GET'&&p.length===3&&p[0]==='rooms'&&uuid(p[1])&&['goals','work'].includes(p[2])){allowed=['after','limit'];uuidCursor=true;}
  else if(method==='GET'&&p.length===2&&['goals','work'].includes(p[0])&&uuid(p[1]))allowed=[];
  else if(method==='GET'&&p.length===3&&p[0]==='work'&&uuid(p[1])&&p[2]==='attempts')allowed=['after','limit'];
  else if(method==='GET'&&p.length===3&&p[0]==='goals'&&uuid(p[1])&&p[2]==='work'){allowed=['after','limit'];uuidCursor=true;}
  else if(method==='GET'&&p.length===4&&p[0]==='attention'&&uuid(p[1])&&p[2]==='commands'&&operation(p[3]))allowed=[];
  else if(method==='GET'&&p.length===3&&p[0]==='rooms'&&uuid(p[1])&&['history','attention'].includes(p[2]))allowed=p[2]==='history'?['after','before','limit']:['after','limit'];
  else if(method==='GET'&&p.length===2&&((p[0]==='commands'&&operation(p[1]))||(['attention','deliveries','native-controls'].includes(p[0])&&uuid(p[1]))))allowed=[];
  else if(method==='POST'&&p.length===3&&p[0]==='rooms'&&uuid(p[1])&&p[2]==='messages'){
    if(!fields(body,['operationId','kind','body','audience','recipientId','sourceEventId'])||!operation(body.operationId)||
      !['message','request'].includes(body.kind)||typeof body.body!=='string'||!body.body.trim()||Buffer.byteLength(body.body)>6000||
      !['room','direct'].includes(body.audience)||body.recipientId!=null&&!uuid(body.recipientId)||body.sourceEventId!=null&&!uuid(body.sourceEventId)||
      body.audience==='direct'&&!body.recipientId)throw fail(400,'Keep an exact room, audience and stable message identity.','HOLLYWOOD_REQUEST_REJECTED');
  }else if(method==='POST'&&p.length===3&&p[0]==='attention'&&uuid(p[1])&&p[2]==='response'){
    if(!fields(body,['operationId','scope','response'])||!operation(body.operationId)||!fields(body.scope,['authorityId','attentionId','deliveryId','agentId','roomId','hostId','generation','nativeThread','nativeTurn','requestDigest'])||
      body.scope.attentionId!==p[1]||!['authorityId','attentionId','deliveryId','agentId','roomId','hostId'].every(key=>uuid(body.scope[key]))||
      !Number.isSafeInteger(body.scope.generation)||body.scope.generation<1||!['nativeThread','nativeTurn'].every(key=>typeof body.scope[key]==='string'&&body.scope[key].length>0&&body.scope[key].length<=128)||
      !/^[0-9a-f]{64}$/.test(body.scope.requestDigest))throw fail(400,'Answer the exact captured Hollywood attention.','HOLLYWOOD_REQUEST_REJECTED');
    const response=body.response;
    const approval=fields(response,['decision'])&&['accept','decline'].includes(response.decision);
    const question=fields(response,['answers'])&&response.answers&&typeof response.answers==='object'&&!Array.isArray(response.answers)&&Object.values(response.answers).every(answer=>fields(answer,['answers'])&&Array.isArray(answer.answers)&&answer.answers.length>0&&answer.answers.length<=12&&answer.answers.every(text=>typeof text==='string'));
    if(!approval&&!question)throw fail(400,'Choose an explicit answer or approval decision.','HOLLYWOOD_REQUEST_REJECTED');
  }else if(method==='POST'&&p.length===3&&p[0]==='deliveries'&&uuid(p[1])&&p[2]==='guidance'){
    const keys=['authorityId','deliveryId','agentId','roomId','hostId','generation','nativeThread','nativeTurn','sourceRequestId','audience'];
    if(!fields(body,['id','operationId','scope','body'])||!uuid(body.id)||!operation(body.operationId)||!fields(body.scope,keys)||Object.keys(body.scope).length!==keys.length||
      body.scope.deliveryId!==p[1]||!['authorityId','deliveryId','agentId','roomId','hostId','sourceRequestId'].every(key=>uuid(body.scope[key]))||
      !Number.isSafeInteger(body.scope.generation)||body.scope.generation<1||!['nativeThread','nativeTurn'].every(key=>typeof body.scope[key]==='string'&&body.scope[key].length>0&&body.scope[key].length<=128)||
      !['room','direct'].includes(body.scope.audience)||typeof body.body!=='string'||!body.body.trim()||Buffer.byteLength(body.body)>6000)
      throw fail(400,'Guide the exact captured active work.','HOLLYWOOD_REQUEST_REJECTED');
  }else if(method==='POST'&&p.length===3&&p[0]==='deliveries'&&uuid(p[1])&&p[2]==='cancel'){
    if(!fields(body,[])||Object.keys(body).length)throw fail(400,'Cancellation uses the exact original delivery.','HOLLYWOOD_REQUEST_REJECTED');
  }else throw fail(404,'Unknown Hollywood human operation.','HOLLYWOOD_REQUEST_REJECTED');
  if(Object.keys(query).some(key=>!allowed.includes(key))||Object.entries(query).some(([key,value])=>key==='after'&&uuidCursor?!uuid(value):typeof value!=='string'||!/^[0-9]{1,16}$/.test(value)||!Number.isSafeInteger(Number(value))))throw fail(400,'Invalid Hollywood query.','HOLLYWOOD_REQUEST_REJECTED');
  if(query.before!==undefined&&query.after!==undefined)throw fail(400,'Choose one history direction.','HOLLYWOOD_REQUEST_REJECTED');
  if(query.limit!==undefined&&(Number(query.limit)<1||Number(query.limit)>100))throw fail(400,'Invalid room page size.','HOLLYWOOD_REQUEST_REJECTED');
  if(body!==undefined&&Buffer.byteLength(JSON.stringify(body))>14000)throw fail(400,'Hollywood operation exceeds the limit.','HOLLYWOOD_REQUEST_REJECTED');
  return {path:p.join('/'),method,query,...(method==='POST'?{body}:{})};
}

/** Mount only after NextComp requireAuth. Registration is private local administration. */
export function mountHollywood(app,{dir,gateway=new HollywoodGateway(loadHollywood(dir))}){
  app.get('/api/hollywood/v3/identity',async(req,res,next)=>{
    try{const client=gateway.client(req.device.id);res.json(await client.verify());}catch(error){next(error);}
  });
  app.use('/api/hollywood/v3',async(req,res,next)=>{
    try{
      const client=gateway.client(req.device.id);
      if(req.get('X-Hollywood-Binding')!==client.config.bindingId)throw fail(409,'This operation belongs to another saved environment or human binding.','HOLLYWOOD_BINDING_MISMATCH');
      const operation=hollywoodRequest(req.method,req.path,req.query,req.body);
      if(operation.body?.scope&&operation.body.scope.authorityId!==client.config.authorityId)throw fail(409,'Attention belongs to another enterprise.','HOLLYWOOD_BINDING_MISMATCH');
      const value=await client.call(operation.path,operation);
      res.json({...value,binding:client.envelope()});
    }catch(error){next(error);}
  });
  return gateway;
}
