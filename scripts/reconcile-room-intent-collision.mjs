/** Local owner-only recovery. Never exposed as an HTTP/model tool. */
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,writeFileSync,lstatSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {prepareIntentCollisionRecovery,applyIntentCollisionRecovery,continueIntentCollisionRecovery} from '../server/coordinator-room-intent-recovery.mjs';
process.umask(0o077);
const args=Object.fromEntries(process.argv.slice(2).map(a=>{const i=a.indexOf('=');if(i<3)throw Error('Use --key=value.');return [a.slice(2,i),a.slice(i+1)];}));
const hash=b=>createHash('sha256').update(b).digest('hex');
function protectedRead(path){const st=lstatSync(path);if(!st.isFile()||(st.mode&0o077)||st.uid!==process.getuid())throw Error('Protected original-owner file required.');return readFileSync(path);}
const file=lstatSync(args.database);if(!file.isFile()||(file.mode&0o077)||file.uid!==process.getuid())throw Error('Use the protected original owner database.');
const sourceSha=hash(readFileSync(args['original-source']));
const db=new DatabaseSync(args.database);db.exec('PRAGMA busy_timeout=1000');
try{
 if(args.action==='prepare'){
  const scope={device:args.device,source:args.source,binding:args.binding,id:args.id};
  const plan=prepareIntentCollisionRecovery(db,scope,sourceSha);
  writeFileSync(args.output,JSON.stringify(plan,null,2)+'\n',{flag:'wx',mode:0o600});
  console.log(JSON.stringify({prepared:true,planSha256:hash(readFileSync(args.output)),decision:plan.decision,noNetworkOrDispatch:true,originalRowsUnchanged:true}));
 }else{
  const bytes=protectedRead(args.plan);if(hash(bytes)!==args['plan-sha256'])throw Error('Exact reviewed plan SHA required.');const plan=JSON.parse(bytes);
  if(args.action==='apply')console.log(JSON.stringify(applyIntentCollisionRecovery(db,plan,sourceSha)));
  else if(args.action==='continue'){
   const key=JSON.parse(protectedRead(args.secrets)).adminToken;
   // Use the existing credential decoder without constructor startup writes.
   const sources=Object.assign(Object.create(CoordinatorSources.prototype),{db,key:createHash('sha256').update('nextcomp-coordinator-source-v1\0'+key).digest(),fetchImpl:fetch,clock:Date.now,
    authorize:actor=>JSON.stringify(actor)===JSON.stringify(plan.actor)});
   const actor=plan.actor,call=(path,body)=>sources.call(plan.scope.device,plan.scope.source,path,body,actor);
   const result=await continueIntentCollisionRecovery(db,plan,{get:path=>call(path),post:call});
   writeFileSync(args.output,JSON.stringify({at:new Date().toISOString(),result},null,2)+'\n',{flag:'wx',mode:0o600});
   console.log(JSON.stringify(result));
  }else throw Error('Choose explicit prepare, apply or continue.');
 }
}finally{db.close();}
