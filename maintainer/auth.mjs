import {readFileSync} from 'node:fs';
import {createSign} from 'node:crypto';
import {execFileSync} from 'node:child_process';

export function appJWT(appId,privateKey,now=Date.now()){
 const enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
 const text=enc({alg:'RS256',typ:'JWT'})+'.'+enc({iat:Math.floor(now/1000)-60,exp:Math.floor(now/1000)+540,iss:String(appId)});
 return text+'.'+createSign('RSA-SHA256').update(text).sign(privateKey,'base64url');
}
export function installationToken(app,{request=fetch,now=Date.now}={}){
 if(!Number.isSafeInteger(app.repositoryId)||!Number.isSafeInteger(app.installationId)||!app.appId)throw Error('Repository-scoped App configuration required');
 let cached=null,pending=null;
 return async()=>{
  if(cached&&cached.expires>now()+120000)return cached.token;
  if(pending)return pending;
  pending=(async()=>{
   const jwt=appJWT(app.appId,app.privateKey||readFileSync(app.privateKeyFile,'utf8'),now());
   const response=await request(`https://api.github.com/app/installations/${app.installationId}/access_tokens`,{method:'POST',redirect:'error',headers:{Authorization:`Bearer ${jwt}`,Accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'},body:JSON.stringify({repository_ids:[app.repositoryId]}),signal:AbortSignal.timeout(30000)});
   if(!response.ok)throw Error(`GitHub App authentication failed (${response.status})`);
   const data=await response.json();
   if(typeof data.token!=='string'||!Number.isFinite(Date.parse(data.expires_at)))throw Error('Invalid installation token response');
   if(data.repositories&&(data.repositories.length!==1||data.repositories[0].id!==app.repositoryId))throw Error('Installation token repository scope mismatch');
   cached={token:data.token,expires:Date.parse(data.expires_at)};return cached.token;
  })();
  try{return await pending;}finally{pending=null;}
 };
}
export function githubCredential(config){
 // Configuring an App disables personal-token fallback, including on failure.
 if(config.githubApp)return installationToken(config.githubApp);
 return process.env.GH_TOKEN||execFileSync('gh',['auth','token'],{encoding:'utf8'}).trim();
}
