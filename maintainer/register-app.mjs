// One-time interactive registration helper. Run behind the existing HTTPS proxy;
// stop it and remove its temporary proxy route after installation.
import {createServer} from 'node:http';
import {randomBytes,timingSafeEqual} from 'node:crypto';
import {readFileSync,writeFileSync,renameSync} from 'node:fs';
import {dirname,join} from 'node:path';
import {appJWT,installationToken} from './auth.mjs';

const configPath=process.env.MAINTAINER_CONFIG;
const config=JSON.parse(readFileSync(configPath,'utf8'));
const directory=dirname(configPath),origin=new URL(process.env.MAINTAINER_SETUP_ORIGIN).origin;
if(!origin.startsWith('https://'))throw Error('HTTPS setup origin required');
const state=randomBytes(32).toString('hex'),route='/setup/'+state,expires=Date.now()+2*3600000;
const callback=origin+route+'/callback';
const manifest={name:'FallSoftCo NextComp Maintainer',url:'https://github.com/FallSoftCo/pocket',public:false,
 hook_attributes:{url:origin+'/github/events',active:false},redirect_url:callback,
 setup_url:origin+route+'/installed',description:'Repository-scoped NextComp maintainer: reviews, CI dispatch, contributor reconsideration, and webhook recovery.',
 default_permissions:{contents:'write',pull_requests:'write',issues:'write',actions:'write',repository_hooks:'write'},default_events:[]};
let app=null,done=false,busy=false;
const htmlEscape=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const server=createServer(async(req,res)=>{
 const send=(status,body)=>{res.writeHead(status,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff'});res.end(body);};
 const url=new URL(req.url,origin);
 if(req.method!=='GET'||Date.now()>expires||done||!url.pathname.startsWith(route)){send(404,'Setup unavailable');return;}
 try{
  if(url.pathname===route){
   send(200,`<!doctype html><meta name="viewport" content="width=device-width"><title>NextComp maintainer setup</title><h1>NextComp maintainer GitHub App</h1><p>Create a private FallSoftCo App, then install it on <strong>only FallSoftCo/pocket</strong>.</p><p>Repository permissions: contents, pull requests, issues, Actions and repository webhooks (read/write). No organization permissions. Review execution remains isolated from these credentials.</p><form method="post" action="https://github.com/organizations/FallSoftCo/settings/apps/new?state=${state}"><input type="hidden" name="manifest" value="${htmlEscape(JSON.stringify(manifest))}"><button>Create GitHub App</button></form>`);return;
  }
  if(url.pathname===route+'/callback'){
   const supplied=url.searchParams.get('state')||'';
   if(supplied.length!==state.length||!timingSafeEqual(Buffer.from(supplied),Buffer.from(state))||!url.searchParams.get('code')||app||busy){send(400,'Invalid or consumed callback');return;}
   busy=true;
   try{
    const response=await fetch('https://api.github.com/app-manifests/'+encodeURIComponent(url.searchParams.get('code'))+'/conversions',{method:'POST',redirect:'error',headers:{Accept:'application/vnd.github+json'},signal:AbortSignal.timeout(30000)});
    if(!response.ok)throw Error('Manifest conversion failed');
    const value=await response.json();if(!value.id||!value.pem||!value.slug)throw Error('Incomplete App registration');
    writeFileSync(join(directory,'github-app.pem'),value.pem,{mode:0o600});
    app={appId:value.id,slug:value.slug,privateKeyFile:join(directory,'github-app.pem'),repositoryId:config.webhook.repositoryId};
    writeFileSync(join(directory,'github-app-registration.json'),JSON.stringify(app),{mode:0o600});
    res.writeHead(302,{Location:`https://github.com/apps/${encodeURIComponent(app.slug)}/installations/new`,'Cache-Control':'no-store','Referrer-Policy':'no-referrer'});res.end();
   }finally{busy=false;}
   return;
  }
  if(url.pathname===route+'/installed'&&app){
   const installationId=Number(url.searchParams.get('installation_id'));
   if(!Number.isSafeInteger(installationId)||installationId<1)throw Error('Installation ID required');
   const jwt=appJWT(app.appId,readFileSync(app.privateKeyFile,'utf8'));
   const r=await fetch(`https://api.github.com/app/installations/${installationId}`,{redirect:'error',headers:{Authorization:`Bearer ${jwt}`,Accept:'application/vnd.github+json'},signal:AbortSignal.timeout(30000)});
   if(!r.ok)throw Error('Installation lookup failed');const installation=await r.json();
   if(installation.account?.login?.toLowerCase()!=='fallsoftco'||installation.repository_selection!=='selected')throw Error('Choose only the NextComp repository');
   // Inspect the installation's entire repository selection before issuing the
   // repository-restricted runtime token. A restricted token would hide extras.
   const issued=await fetch(`https://api.github.com/app/installations/${installationId}/access_tokens`,{method:'POST',redirect:'error',headers:{Authorization:`Bearer ${jwt}`,Accept:'application/vnd.github+json','Content-Type':'application/json'},body:'{}',signal:AbortSignal.timeout(30000)});
   if(!issued.ok)throw Error('Installation verification failed');
   const {token}=await issued.json();
   const repos=await fetch('https://api.github.com/installation/repositories',{redirect:'error',headers:{Authorization:`Bearer ${token}`,Accept:'application/vnd.github+json'},signal:AbortSignal.timeout(30000)});
   const data=await repos.json();
   if(!repos.ok||data.total_count!==1||data.repositories[0]?.id!==app.repositoryId)throw Error('NextComp repository installation required');
   const current=JSON.parse(readFileSync(configPath,'utf8'));
   current.githubApp={...app,installationId};delete current.githubApp.slug;
   writeFileSync(configPath+'.tmp',JSON.stringify(current,null,2)+'\n',{mode:0o600});renameSync(configPath+'.tmp',configPath);
   done=true;send(200,'<h1>NextComp App installed</h1><p>The repository-scoped credential is ready. Codex will finish switching the worker and remove the old personal token.</p>');
   console.log('Repository-scoped GitHub App configured');return;
  }
  send(404,'Not found');
 }catch{send(400,'Setup could not complete. Check the selected organization and repository, then retry. No credentials are shown here.');}
});
server.listen(Number(process.env.MAINTAINER_SETUP_PORT||18882),'127.0.0.1',()=>{
 writeFileSync(join(directory,'github-app-setup-url'),origin+route+'\n',{mode:0o600});
 console.log('One-time GitHub App registration ready');
});
setTimeout(()=>server.close(),2*3600000).unref();
