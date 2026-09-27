import {GoogleAuth} from 'google-auth-library';
import {mkdirSync,readFileSync,writeFileSync,chmodSync} from 'node:fs';
import {resolve} from 'node:path';
import {dataDir} from './local-client.mjs';
const [projectId,keyFile]=process.argv.slice(2);
if(!projectId||!keyFile)throw Error('Usage: node scripts/configure-firebase.mjs PROJECT_ID SERVICE_ACCOUNT_JSON');
const credentials=JSON.parse(readFileSync(keyFile));
const auth=new GoogleAuth({credentials,scopes:['https://www.googleapis.com/auth/cloud-platform']});
const client=await auth.getClient();
const base='https://firebase.googleapis.com/v1beta1';
const request=async(path,method='GET',data)=> (await client.request({url:base+'/'+path,method,...(data?{data}:{})})).data;
const apps=await request(`projects/${projectId}/androidApps`);
let app=apps.apps?.find(a=>a.packageName==='co.fallsoft.pocket');
if(!app){
 const op=await request(`projects/${projectId}/androidApps`,'POST',{packageName:'co.fallsoft.pocket',displayName:'Pocket — Codex companion'});
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
const dir=dataDir;mkdirSync(dir,{recursive:true,mode:0o700});chmodSync(dir,0o700);
writeFileSync(resolve(dir,'firebase-client.json'),JSON.stringify(publicConfig,null,2)+'\n',{mode:0o600});
writeFileSync(resolve(dir,'firebase-admin.json'),JSON.stringify(credentials)+'\n',{mode:0o600});
chmodSync(resolve(dir,'firebase-admin.json'),0o600);
chmodSync(resolve(dir,'firebase-client.json'),0o600);
console.log(JSON.stringify({configured:true,projectId:publicConfig.projectId,packageName:'co.fallsoft.pocket',appName:app.name}));
