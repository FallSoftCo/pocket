/** Explicit local owner prepare/apply. No network, credential, voice-history,
 * role or original receipt writes. Review/pin the emitted plan before apply. */
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,lstatSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {prepareNoAdmission,applyNoAdmission} from '../server/coordinator-room-reconciliation.mjs';
const args=Object.fromEntries(process.argv.slice(2).map(a=>{const i=a.indexOf('=');if(i<3)throw Error('Use --key=value arguments.');return [a.slice(2,i),a.slice(i+1)];}));
const hash=b=>createHash('sha256').update(b).digest('hex');
function protectedInput(path,sha){const s=lstatSync(path);if(!s.isFile()||(s.mode&0o022)||![0,process.getuid()].includes(s.uid))throw Error('Reviewed owner file protections required.');const b=readFileSync(path);if(hash(b)!==sha)throw Error('Exact expected owner-file SHA required.');return JSON.parse(b);}
process.umask(0o077);
const proof=protectedInput(args.proof,args['proof-sha256']);
const original=lstatSync(args.database);if(!original.isFile()||(original.mode&0o077)||original.uid!==process.getuid())throw Error('Run as the original protected database owner; do not chown or relax it.');
const db=new DatabaseSync(args.database);db.exec('PRAGMA busy_timeout=1000');
try{
 if(args.action==='prepare'){
  const scope={device:args.device,source:args.source,binding:args.binding,operation:args.operation,remoteId:args['remote-command']};
  const plan=prepareNoAdmission(db,scope,proof,args['proof-sha256']);writeFileSync(args.output,JSON.stringify(plan,null,2)+'\n',{flag:'wx',mode:0o600});console.log(JSON.stringify({prepared:true,planSha256:hash(readFileSync(args.output)),noWritesToOriginalRows:true}));
 }else if(args.action==='apply'){
  const plan=protectedInput(args.plan,args['plan-sha256']);console.log(JSON.stringify(applyNoAdmission(db,plan,proof,args['proof-sha256'])));
 }else throw Error('Choose explicit prepare or apply.');
}finally{db.close();}
