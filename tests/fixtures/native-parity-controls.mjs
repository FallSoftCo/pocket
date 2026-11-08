// Disposable Android UI authority. No provider, shell, model or host operations.
// PORT and PORT+1 must be reversed to emulator loopback 18880 and 18881.
import http from 'node:http';
import crypto from 'node:crypto';
import {projectNativeTimeline} from '../../server/native-timeline.mjs';
import {NativeExecution} from '../../server/native-execution.mjs';
import {QuestionActions} from '../../server/question-actions.mjs';
import {requestPresentation} from '../../server/native-request-contract.mjs';

const port=Number(process.env.PORT||19991);
const threadId='parity-thread',forkId='parity-fork',otherId='parity-other';
const origins=['Alpha','Beta'].map((name,index)=>({name,index,sockets:new Set(),epoch:crypto.randomUUID(),revision:1,question:0,operations:new Map(),fork:false,terminalMode:'qualified',outputMode:'qualified',outputWaiters:[],commandVisible:false,pluginInstalled:false,pluginReceipts:[]}));
const calls=[],nativeAnswers=[],nativeReads=[];
function executionFeatures(origin){
 if(origin.terminalMode==='missing')return undefined;
 const readOnly=origin.terminalMode==='read-only'||origin.outputMode==='unavailable';
 return {backgroundTerminals:{state:'available',readOnly,mutation:readOnly?{state:'unavailable',reason:'Synthetic runtime permits terminal inspection only.'}:{state:'available'}},executionOutput:readOnly?{state:'unavailable',reason:'Synthetic runtime has no retained-output support.'}:{state:'available'}};
}
function frame(socket,value){const b=Buffer.from(JSON.stringify(value));socket.write(Buffer.concat([b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]),b]));}
function head(origin,id=threadId){return {threadId:id,epoch:origin.epoch,version:origin.revision};}
function changed(origin){origin.revision++;for(const socket of origin.sockets)frame(socket,{type:'timeline',threadId,version:origin.revision,reload:true,historyHead:head(origin)});}
function metadata(origin,id=threadId){return {id,name:`${origin.name} ${id===forkId?'branch':id===otherId?'other work':'parity work'}`,cwd:`/synthetic/${origin.name.toLowerCase()}`,canAcceptDirectInput:true,status:{type:'idle'},preview:`${origin.name} response`,previewKind:'message',previewRole:'codex',recencyAt:1794017100000+origin.index,activityAt:1794017100000+origin.index};}
function pending(origin){
 if(!origin.question)return [];
 let method='item/tool/requestUserInput',params={threadId,questions:[{id:'secret',question:`Synthetic secret version ${origin.question}`,isSecret:true,options:[]}]};
 if(origin.question===3){method='mcpServer/elicitation/request';params={threadId,mode:'form',message:'Synthetic native form',requestedSchema:{type:'object',required:['scope'],properties:{scope:{type:'string',title:'Review scope'}}}};}
 if(origin.question>=4){
  method='item/commandExecution/requestApproval';
  const amendment={acceptWithExecpolicyAmendment:{execpolicy_amendment:['synthetic',origin.question===4?'original':'replacement']}};
  params={threadId,turnId:origin.name.toLowerCase()+'-turn',itemId:'shared-command',command:'synthetic preview only',reason:`${origin.name} synthetic command approval ${origin.question}`,availableDecisions:origin.question===4?[amendment,'decline']:['decline',amendment]};
 }
 const request={id:'same-native-request',method,params};request._pocketUI={...requestPresentation(request),token:`request-token-${origin.question}`};return [request];
}
function retainedOutput(origin){return `${origin.name} retained output page one\n`.padEnd(24000,'0123456789 ')+`${origin.name} retained output page two\n`+'Second-page synthetic detail.\n'.repeat(50);}
function timeline(origin,id){
 const turnId=origin.name.toLowerCase()+'-turn';
 const source={data:[{type:'turnStarted',position:1,turnId,startedAt:1},{type:'item',position:2,turnId,item:{id:'user',type:'userMessage',content:[{type:'text',text:`${origin.name} original request`}]}},{type:'item',position:3,turnId,item:{id:'answer',type:'agentMessage',text:`${origin.name} ${id===forkId?'branch response':'response'}`}},{type:'turnCompleted',position:4,turnId,status:'completed'}],nextCursor:null,activeRealtimeSessionAtPageStart:null};
 if(origin.commandVisible&&id===threadId){source.data.splice(2,0,{type:'item',position:3,turnId,item:{id:'shared-command',type:'commandExecution',command:'Synthetic retained-output command',cwd:metadata(origin).cwd,status:'completed',aggregatedOutput:'Synthetic excerpt only',exitCode:0}});source.data.forEach((entry,index)=>{entry.position=index+1;});}
 return {thread:metadata(origin,id),timeline:projectNativeTimeline(source,{threadId:id,revision:origin.revision}),revision:origin.revision,historyHead:head(origin,id),pending:id===threadId?pending(origin):[],notes:[],outgoing:[],notifications:[]};
}
for(const origin of origins){
 const codex={initializeResult:{userAgent:'codex/0.167.0'},async connect(){this.initializeResult.userAgent=origin.outputMode==='unavailable'||origin.terminalMode==='read-only'?'codex_pocket/0.163.0 (Mac OS 26.6.0; arm64)':'codex/0.167.0';},async call(method,params){
  nativeReads.push({origin:origin.name,method,params});
  if(method!=='thread/items/read'||params.threadId!==threadId||params.turnId!==origin.name.toLowerCase()+'-turn'||params.itemIds.length!==1||params.itemIds[0]!=='shared-command')throw Object.assign(Error('Foreign synthetic output identity'),{status:409});
  return {data:[{turnId:params.turnId,item:{id:'shared-command',type:'commandExecution',aggregatedOutput:retainedOutput(origin),status:'completed',exitCode:0}}]};
 }};
 const execution=new NativeExecution(codex);
 const authorized=req=>new RegExp('^Bearer synthetic-parity-'+origin.index+'-[a-f0-9-]{36}$').test(req.headers.authorization||'');
 const server=http.createServer(async(req,res)=>{
  res.setHeader('Content-Type','application/json');
  if(!authorized(req)){res.writeHead(401).end('{"error":"Synthetic credential required"}');return;}
  const url=new URL(req.url,'http://localhost'),path=url.pathname;
  let raw='';for await(const chunk of req)raw+=chunk;
  const input=raw?JSON.parse(raw):{};
  calls.push({origin:origin.name,path,query:url.search,method:req.method,input});
  let body={};
  if(path==='/_qa/state')body={calls:calls.filter(c=>!c.path.startsWith('/_qa/')),operations:[...origin.operations.values()],nativeAnswers,nativeReads};
  else if(path==='/_qa/output-mode'){
   if(!['qualified','unavailable','hold'].includes(input.mode)){res.writeHead(400).end('{"error":"Unknown synthetic output mode"}');return;}
   origin.outputMode=input.mode;body={ok:true};
  }
  else if(path==='/_qa/output-release'){for(const release of origin.outputWaiters.splice(0))release();body={ok:true};}
  else if(path==='/_qa/command'){origin.commandVisible=input.enabled===true;changed(origin);body={ok:true};}
  else if(path==='/_qa/terminal-mode'){
   if(!['qualified','read-only','missing'].includes(input.mode)){res.writeHead(400).end('{"error":"Unknown synthetic terminal mode"}');return;}
   origin.terminalMode=input.mode;body={ok:true};
  }
  else if(path==='/_qa/question'){origin.question=input.version;changed(origin);body={ok:true};}
  else if(path==='/_qa/resolve-fork'){for(const op of origin.operations.values())if(op.kind==='fork')op.state='accepted';body={ok:true};}
  else if(path==='/api/backends')body={backends:[{id:'codex',configured:true,available:true}]};
  else if(path==='/api/status')body={host:origin.name,connected:true,deviceId:`parity-device-${origin.index}`,capabilities:{readOnlyTimeline:true,canonicalTimeline:1},...(url.searchParams.has('threadId')?{historyHead:head(origin,url.searchParams.get('threadId'))}:{})};
  else if(path==='/api/threads')body={threads:[metadata(origin),metadata(origin,otherId),...(origin.fork?[metadata(origin,forkId)]:[])]};
  else if(/^\/api\/threads\/[^/]+\/native-timeline$/.test(path))body=timeline(origin,path.split('/')[3]);
  else if(/^\/api\/threads\/[^/]+(?:\/head)?$/.test(path))body=timeline(origin,path.split('/')[3]);
  else if(path==='/api/requests/same-native-request/answer'){
   const requests=new Map(pending(origin).map(request=>[String(request.id),request]));
   const actions=new QuestionActions({pending:requests,codex:{answer:(id,result)=>nativeAnswers.push({origin:origin.name,id,threadId,turnId:requests.get(String(id))?.params.turnId,itemId:requests.get(String(id))?.params.itemId,requestToken:requests.get(String(id))?._pocketUI.token,result})}});
   try{body=actions.answer('same-native-request',input);origin.question=0;changed(origin);}catch(error){res.writeHead(error.status||500).end(JSON.stringify({error:error.message}));return;}
  }
  else if(path.endsWith('/conversation-tools'))body={threadId,cwd:metadata(origin).cwd,active:false,warning:'Context changes do not undo working files.',usage:{last:{totalTokens:1234},total:{totalTokens:5678},modelContextWindow:10000},turns:[{id:origin.name.toLowerCase()+'-turn',status:'completed',userText:`${origin.name} original request`}],features:Object.fromEntries(['fork','revert','compact','review','changeDirectory'].map(k=>[k,{available:true}])),operations:[...origin.operations.values()]};
  else if(path.endsWith('/conversation-operations')&&req.method==='POST'){
   if(input.turnId&&input.turnId!==origin.name.toLowerCase()+'-turn'){res.writeHead(409).end('{"error":"Foreign turn boundary"}');return;}
   body={id:input.id,threadId,kind:input.kind,state:input.kind==='fork'?'unknown':'accepted',result:{}};
   if(input.kind==='fork'){origin.fork=true;body.thread=metadata(origin,forkId);body.error='Synthetic lost acknowledgement; inspect original receipt.';}
   if(input.kind==='revert'){changed(origin);body.result={draft:`${origin.name} original request`,historyHead:head(origin)};}
   origin.operations.set(body.id,body);
  }
  else if(/\/conversation-operations\/[^/]+$/.test(path)){body=origin.operations.get(path.split('/').at(-1));if(!body){res.writeHead(404).end('{"error":"Original receipt missing"}');return;}}
  else if(path.endsWith('/command-output')){
   try{
    body=await execution.output(path.split('/')[3],url.searchParams.get('turnId'),url.searchParams.get('itemId'),{offset:Number(url.searchParams.get('offset')||0),revision:url.searchParams.get('revision')||null});
    if(origin.outputMode==='hold'){const result=JSON.stringify(body);origin.outputWaiters.push(()=>{if(!res.destroyed)res.end(result);});return;}
   }catch(error){res.writeHead(error.status||500).end(JSON.stringify({error:error.message}));return;}
  }
  else if(path==='/api/native/capabilities')body={runtime:{version:origin.terminalMode==='read-only'||origin.outputMode==='unavailable'?'0.163.0':'0.167.0'},features:executionFeatures(origin)};
  else if(path.endsWith('/terminals')&&executionFeatures(origin)?.backgroundTerminals?.readOnly&&(url.searchParams.getAll('terminalPresentation').length!==1||url.searchParams.get('terminalPresentation')!=='1')){res.writeHead(409).end('{"error":"Update this client to inspect read-only background terminals in this environment."}');return;}
  else if(path.endsWith('/terminals'))body={data:[{processId:'shared-process-id',command:`${origin.name} synthetic watcher`,cwd:metadata(origin).cwd,cpuPercent:0}],nextCursor:null,features:executionFeatures(origin)};
  else if(path.endsWith('/terminals/terminate')&&executionFeatures(origin)?.backgroundTerminals?.mutation?.state!=='available'){res.writeHead(409).end('{"error":"Synthetic runtime does not permit termination."}');return;}
  else if(path.endsWith('/terminals/terminate'))body={ok:true,terminated:true};
  else if(path==='/api/native/plugins/detail'){
   const key=crypto.createHash('sha256').update('synthetic-plugin').digest('hex'),reviewToken=crypto.createHash('sha256').update(origin.name+':'+origin.pluginInstalled).digest('hex');
   if(url.searchParams.get('key')!==key){res.writeHead(409).end('{"error":"Synthetic plugin identity mismatch"}');return;}
   body={threadId,reviewToken,detail:{key,name:origin.name+' synthetic plugin',marketplace:'Synthetic marketplace',installed:origin.pluginInstalled,enabled:origin.pluginInstalled,installPolicy:'AVAILABLE',authPolicy:'ON_INSTALL',mustShowInstallationInterstitial:true,description:'Synthetic plugin capability notice',skills:[],hooks:[],mcpServers:['fixture-mcp'],apps:[],links:{},capabilities:['Synthetic task inspection']},canInstall:!origin.pluginInstalled,canUninstall:origin.pluginInstalled,effects:'Installation may open a browser on the host and save credentials. This remote client cannot continue host authentication. Installation does not establish readiness.',removalEffects:'Removal leaves bundled app authorization unchanged.',receipts:origin.pluginReceipts};
  }
  else if(path==='/api/native/plugins/receipts')body={receipts:origin.pluginReceipts};
  else if(path==='/api/native/configuration'&&req.method==='POST'){
   const key=crypto.createHash('sha256').update('synthetic-plugin').digest('hex'),reviewToken=crypto.createHash('sha256').update(origin.name+':'+origin.pluginInstalled).digest('hex');
   if(input.section!=='plugins'||input.action!=='install'||input.key!==key||input.threadId!==threadId||input.reviewToken!==reviewToken||input.confirmEffects!==true||input.confirmInterstitial!==true||!input.attemptId){res.writeHead(409).end('{"error":"Synthetic source or explicit plugin confirmation missing"}');return;}
   origin.pluginInstalled=true;body={attemptId:input.attemptId,key,name:origin.name+' synthetic plugin',action:'install',state:'acknowledged',readiness:'unverified',appsNeedingAuth:[{id:'fixture-app',name:'Synthetic connector'}],notice:'Installation acknowledged. Some apps need authentication. Host authentication may still be pending. Readiness is unverified.'};origin.pluginReceipts.push(body);
  }
  else if(path==='/api/native/configuration'){
   const section=url.searchParams.get('section')||'settings';
   body={state:'available',host:origin.name,runtime:{version:'0.167.0 synthetic'},requested:{permissions:'full'},observed:{state:'observed',settings:{sandbox:{type:'danger-full-access'},approvalPolicy:'never'},notice:'Synthetic observation'},settings:{model:'synthetic-codex'},configVersion:'parity-config-1',items:section==='skills'?[{id:'qa-skill',name:'Synthetic skill',description:'Visible from the actual native settings catalog.',enabled:true,configurable:false}]:section==='plugins'?[{id:'synthetic-plugin',key:crypto.createHash('sha256').update('synthetic-plugin').digest('hex'),name:origin.name+' synthetic plugin',inspectable:true}]:[],features:{}};
  }
  else if(path==='/api/notifications'||path==='/api/attention'||path==='/api/activity')body={notifications:[],items:[]};
  else if(path==='/api/app/update')body={available:false};
  else if(path.startsWith('/api/voice/')){res.writeHead(503).end('{"error":"Fixture prohibits provider audio"}');return;}
  else if(req.method!=='GET'&&!['/api/device/preferences','/api/device/push'].includes(path)&&!path.endsWith('/read')){res.writeHead(403).end('{"error":"Unexpected mutation blocked by fixture"}');return;}
  res.end(JSON.stringify(body));
 });
 server.on('upgrade',(req,socket)=>{
  if(!authorized(req)){socket.destroy();return;}
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');
  origin.sockets.add(socket);socket.on('close',()=>origin.sockets.delete(socket));socket.on('error',()=>origin.sockets.delete(socket));frame(socket,{type:'status',connected:true});
 });
 server.listen(port+origin.index,'127.0.0.1',()=>console.log(`Native parity fixture ${origin.name}: ${port+origin.index}`));
}
