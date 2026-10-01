import {GitHub} from './github.mjs';
import {sendFailurePush} from './alerts.mjs';

// Runs outside the worker host. It checks liveness, never polls PRs or reviews.
const gh=new GitHub(process.env.GITHUB_TOKEN);
try{
 const url=new URL(process.env.MAINTAINER_HEALTH_URL);
 if(url.protocol!=='https:')throw Error('HTTPS health endpoint required');
 const response=await fetch(url,{redirect:'error',signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw Error(`Maintainer health returned ${response.status}`);
 console.log('Maintainer healthy');
}catch{
 const {workflow_runs:runs}=await gh.request(`${gh.root}/actions/workflows/maintainer-health.yml/runs?branch=main&per_page=100`);
 const previous=runs.filter(r=>String(r.id)!==process.env.GITHUB_RUN_ID&&r.status==='completed')[0];
 const today=new Date().toISOString().slice(0,10);
 const alreadyToday=runs.some(r=>String(r.id)!==process.env.GITHUB_RUN_ID&&r.status==='completed'&&r.conclusion==='failure'&&r.created_at.startsWith(today));
 if(previous?.conclusion!=='failure'||!alreadyToday){
  await sendFailurePush({credentials:JSON.parse(process.env.MAINTAINER_FIREBASE_CREDENTIALS),targets:JSON.parse(process.env.MAINTAINER_ALERT_TARGETS)},
   {key:'host-health',title:'Pocket maintainer is unavailable',body:'The independent health check could not reach a healthy worker. Automatic PR handling may be delayed. Check the Hetzner service.'});
 }
 throw Error('Maintainer health check failed');
}
