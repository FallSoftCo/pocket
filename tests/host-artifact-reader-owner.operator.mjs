import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,statSync,chownSync,chmodSync,existsSync,rmSync} from 'node:fs';
import {execFileSync,spawnSync} from 'node:child_process';
import {createHash,generateKeyPairSync,randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {createReaderRequest,acceptReader} from '../scripts/artifact-reader-enrollment.mjs';
import {privateNodeFile} from '../scripts/artifact-local-files.mjs';
const digest=b=>createHash('sha256').update(b).digest('hex');
const identity=p=>{const s=statSync(p);return {uid:s.uid,gid:s.gid,mode:s.mode&0o777,device:s.dev,inode:s.ino};};

test('real root-parent/service-owner config acceptance stays narrow and preserves original identities',()=>{
 assert.equal(process.platform,'linux');assert.equal(process.getuid(),0,'Run this one explicit owner fixture as root; never skip it as qualified');
 const owner=Number(execFileSync('id',['-u','aosp'],{encoding:'utf8'}).trim());
 const group=Number(execFileSync('id',['-g','aosp'],{encoding:'utf8'}).trim());assert.notEqual(owner,0);
 const root=mkdtempSync('/run/nextcomp-reader-config-owner-qa-');chmodSync(root,0o755);
 try{
  const parent=join(root,'gateway');mkdirSync(parent,{mode:0o750});chownSync(parent,0,group);
  const configFile=join(parent,'fleet.json'),pair=generateKeyPairSync('ed25519'),node=randomUUID();
  const config={version:1,fleetId:randomUUID(),accountId:randomUUID(),nodeId:node,
   members:[{id:node,name:'fixture-only',origin:'https://fixture.invalid',publicKey:pair.publicKey.export({type:'spki',format:'pem'})}]};
  writeFileSync(configFile,JSON.stringify(config),{mode:0o600});chownSync(configFile,owner,group);
  const original=readFileSync(configFile),parentBefore=identity(parent),fileBefore=identity(configFile);
  const own=spawnSync(process.execPath,['--input-type=module','-e',
   `import fs from 'node:fs';fs.readFileSync(process.argv[1]);try{fs.writeFileSync(process.argv[2],'x',{flag:'wx'});process.exit(2)}catch(e){if(e.code!=='EACCES')throw e}`,
   configFile,join(parent,'owner-cannot-create')],{uid:owner,gid:group,encoding:'utf8',timeout:5000});
  assert.equal(own.status,0,own.stderr);assert.ok(!existsSync(join(parent,'owner-cannot-create')));
  assert.throws(()=>privateNodeFile(configFile,65536)); // Ordinary ROOT key/file reader still rejects UID1000.
  const local=join(root,'local'),operation=join(root,'accept');mkdirSync(local,{mode:0o700});mkdirSync(operation,{mode:0o700});
  const nodeId=randomUUID(),challenge=randomUUID();createReaderRequest({dir:local,nodeId,challenge});
  const requestFile=join(local,'request.public.json');
  const args={configFile,requestFile,requestSha256:digest(readFileSync(requestFile)),nodeId,challenge,dir:operation};
  assert.throws(()=>acceptReader(args));assert.deepEqual(readFileSync(configFile),original);
  assert.ok(!existsSync(join(operation,'accept-intent.private.json')));
  const reviewFile=join(root,'owner-review.private.json'),review={schema:'nextcomp-artifact-reader-config-owner-review/v1',configFile,
   expectedConfigSha256:digest(original),file:fileBefore,parent:parentBefore};
  const wrong={...review,file:{...fileBefore,uid:owner+1}};writeFileSync(reviewFile,JSON.stringify(wrong),{mode:0o600});
  assert.throws(()=>acceptReader({...args,configOwnerReviewFile:reviewFile}));assert.deepEqual(readFileSync(configFile),original);
  writeFileSync(reviewFile,JSON.stringify(review));
  assert.equal(acceptReader({...args,configOwnerReviewFile:reviewFile}).registered,true);
  const next=JSON.parse(readFileSync(configFile));assert.deepEqual(next.members,config.members);assert.equal(next.artifactReaders[0].id,nodeId);
  assert.deepEqual(identity(parent),parentBefore);
  const installed=identity(configFile);for(const key of ['uid','gid','mode','device'])assert.equal(installed[key],fileBefore[key]);
  const after=readFileSync(configFile),inode=installed.inode;
  assert.equal(acceptReader({...args,configOwnerReviewFile:reviewFile}).registered,true);
  assert.deepEqual(readFileSync(configFile),after);assert.equal(statSync(configFile).ino,inode);
  assert.throws(()=>privateNodeFile(configFile,65536)); // Privileged CONFIG path did not broaden ordinary readers.
  console.log(JSON.stringify({realUnequalOwner:true,serviceUid:owner,rootOnlyParent:true,preservedOwnerModeAndParent:true,
   ordinaryPrivateReaderStillDenied:true,acceptedOriginalOperationReconciledWithoutRewrite:true,productionConfigRead:false}));
 }finally{rmSync(root,{recursive:true,force:true});}
});
