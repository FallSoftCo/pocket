import {readFileSync} from 'node:fs';
import {createSign,createHash} from 'node:crypto';

// A separate Firebase service account needs only cloudmessaging.messages.create.
// Notification messages render through Android even while the workstation is off.
export async function sendFailurePush(config,{key,title,body},{request=fetch}={}){
 const credentials=config.credentials||JSON.parse(readFileSync(config.credentialsFile,'utf8'));
 const targets=config.targets||JSON.parse(readFileSync(config.targetsFile,'utf8'));
 if(!targets.length)throw Error('No failure alert device configured');
 const enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
 const now=Math.floor(Date.now()/1000);
 const unsigned=enc({alg:'RS256',typ:'JWT'})+'.'+enc({iss:credentials.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
 const assertion=unsigned+'.'+createSign('RSA-SHA256').update(unsigned).sign(credentials.private_key,'base64url');
 const token=await request('https://oauth2.googleapis.com/token',{method:'POST',redirect:'error',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion}),signal:AbortSignal.timeout(30000)});
 if(!token.ok)throw Error(`Failure alert authentication failed (${token.status})`);
 const {access_token}=await token.json();
 for(const target of targets){
  const response=await request(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(credentials.project_id)}/messages:send`,{method:'POST',redirect:'error',headers:{Authorization:`Bearer ${access_token}`,'Content-Type':'application/json'},body:JSON.stringify({message:{token:target.token,notification:{title,body},data:{operations:'failure',device_id:target.device_id,operations_url:'https://github.com/FallSoftCo/pocodex/actions'},android:{priority:'high',ttl:'86400s',restricted_package_name:'co.fallsoft.pocket',notification:{channel_id:'work',tag:'pocket-ops-'+createHash('sha256').update(key).digest('hex').slice(0,16),icon:'ic_notification',visibility:'PRIVATE'}}}}),signal:AbortSignal.timeout(30000)});
  if(!response.ok)throw Error(`Failure alert delivery failed (${response.status})`);
 }
}

export function failureNotifier(config,state,{send=sendFailurePush,now=Date.now}={}){
 return async(key,title,body)=>{
  if(config.notifyErrors!==true)return;
  if(!config.alerts)throw Error('Failure alerts enabled without a delivery configuration');
  const receipt='alert:'+key,prior=state.get(receipt);
  if(prior&&now()-prior.at<6*3600000)return;
  await send(config.alerts,{key,title,body});state.set(receipt,{at:now()});
 };
}
