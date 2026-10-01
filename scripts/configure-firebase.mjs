import {GoogleAuth} from 'google-auth-library';
import {mkdirSync,readFileSync,writeFileSync,chmodSync} from 'node:fs';
import {resolve} from 'node:path';
import {dataDir} from './local-client.mjs';
async function main(){
const [projectId,keyFile,senderKeyFile,...extra]=process.argv.slice(2);
if(!projectId||!keyFile||extra.length||!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)){
 console.error('Usage: node scripts/configure-firebase.mjs PROJECT_ID SETUP_SERVICE_ACCOUNT_JSON [SENDING_SERVICE_ACCOUNT_JSON]');process.exitCode=2;return;
}
const credentials=JSON.parse(readFileSync(keyFile));
const sender=senderKeyFile?JSON.parse(readFileSync(senderKeyFile)):credentials;
for(const c of [credentials,sender])if(c.type!=='service_account'||typeof c.private_key!=='string'||typeof c.client_email!=='string')throw Error('Invalid service-account file');
const auth=new GoogleAuth({credentials,scopes:['https://www.googleapis.com/auth/cloud-platform']});
const client=await auth.getClient();
const base='https://firebase.googleapis.com/v1beta1';
const request=async(path,method='GET',data)=> (await client.request({url:base+'/'+path,method,timeout:30000,...(data?{data}:{})})).data;
let app,pageToken;
for(let page=0;page<10&&!app;page++){
 const apps=await request(`projects/${projectId}/androidApps?pageSize=100${pageToken?'&pageToken='+encodeURIComponent(pageToken):''}`);
 app=apps.apps?.find(a=>a.packageName==='co.fallsoft.pocket'&&a.state!=='DELETED');
 pageToken=apps.nextPageToken;if(!pageToken)break;
}
if(!app&&pageToken)throw Error('App listing exceeds setup limit');
if(!app){
 const op=await request(`projects/${projectId}/androidApps`,'POST',{packageName:'co.fallsoft.pocket',displayName:'Pocodex — Codex companion'});
 for(let n=0;n<60;n++){
  const result=await request(op.name);
  if(result.error)throw Error(result.error.message);
  if(result.done){app=result.response;break;}
  await new Promise(r=>setTimeout(r,1000));
 }
}
if(!app?.name)throw Error('Firebase app registration did not finish. Rerun to check it.');
const raw=await request(`${app.name}/config`);
const config=JSON.parse(Buffer.from(raw.configFileContents,'base64').toString());
const android=config.client.find(c=>c.client_info.android_client_info.package_name==='co.fallsoft.pocket');
const publicConfig={projectId:config.project_info.project_id,senderId:config.project_info.project_number,applicationId:android.client_info.mobilesdk_app_id,apiKey:android.api_key[0].current_key};
if(publicConfig.projectId!==projectId||Object.values(publicConfig).some(x=>typeof x!=='string'||!x))throw Error('Firebase client configuration is incomplete');
const dir=dataDir;mkdirSync(dir,{recursive:true,mode:0o700});chmodSync(dir,0o700);
writeFileSync(resolve(dir,'firebase-client.json'),JSON.stringify(publicConfig,null,2)+'\n',{mode:0o600});
writeFileSync(resolve(dir,'firebase-admin.json'),JSON.stringify(sender)+'\n',{mode:0o600});
chmodSync(resolve(dir,'firebase-admin.json'),0o600);
chmodSync(resolve(dir,'firebase-client.json'),0o600);
console.log(JSON.stringify({configured:true,projectId:publicConfig.projectId,packageName:'co.fallsoft.pocket',appName:app.name,separateSender:!!senderKeyFile}));
if(!senderKeyFile)console.log('Setup credential copied for sending. Use a separate push-only credential for ongoing operation; see docs/FIREBASE.md.');
}
// Google API exceptions can contain request headers and credentials. Never dump
// them into a terminal or a support report.
try{await main();}catch(e){
 const status=e.response?.status;
 console.error(`Firebase setup failed${status?' (HTTP '+status+')':''}. Check the project ID, enabled APIs, private key files and setup permissions in docs/FIREBASE.md. IAM changes may take several minutes to propagate. No credential details were logged.`);
 process.exitCode=1;
}
