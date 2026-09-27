import {pathToFileURL} from 'node:url';
import {readFileSync} from 'node:fs';
import {GitHub} from './github.mjs';
import {githubCredential} from './auth.mjs';
import {EventQueue} from './events.mjs';
import {failureNotifier} from './alerts.mjs';

export function missedDeliveries(deliveries,now=Date.now()){
 const groups=new Map();
 for(const d of deliveries){
  if(!groups.has(d.guid))groups.set(d.guid,[]);groups.get(d.guid).push(d);
 }
 return [...groups.values()].filter(rows=>!rows.some(d=>d.status_code>=200&&d.status_code<300))
  .map(rows=>rows.sort((a,b)=>Date.parse(b.delivered_at)-Date.parse(a.delivered_at))[0])
  .filter(d=>Date.parse(d.delivered_at)>now-3*86400000&&Date.parse(d.delivered_at)<now-60000)
  .slice(0,20);
}
export async function recover(gh,hookId){
 if(!Number.isSafeInteger(hookId)||hookId<1)throw Error('Webhook ID required');
 const rows=await gh.pages(`${gh.root}/hooks/${hookId}/deliveries`);
 const missed=missedDeliveries(rows);
 for(const d of missed){
  if(!/^\d+$/.test(String(d.id)))throw Error('Invalid delivery ID');
  await gh.request(`${gh.root}/hooks/${hookId}/deliveries/${d.id}/attempts`,{method:'POST'});
 }
 return missed.length;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const config=JSON.parse(readFileSync(process.env.MAINTAINER_CONFIG,'utf8'));
 const queue=new EventQueue(config.dataDir+'/events.sqlite');
 try{console.log(JSON.stringify({redelivered:await recover(new GitHub(githubCredential(config)),config.webhook.hookId)}));}
 catch(e){await failureNotifier(config,queue)('webhook-recovery','Pocket webhook recovery failed','Missed-delivery recovery could not contact GitHub. Check the maintainer recovery service.');throw e;}
 finally{queue.close();}
}
