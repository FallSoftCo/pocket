import {spawnSync} from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,mkdirSync,rmSync,symlinkSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {sha256,nativeOutputPassed,validateReleaseQualification} from '../scripts/release-qualification.mjs';
import {publishQualifiedUpdate} from '../scripts/publish-qualified-update.mjs';
const digest=v=>sha256(Buffer.from(JSON.stringify(v)));
function fixture(){
 const root=mkdtempSync(join(tmpdir(),'nextcomp-release-gate-')),directory=join(root,'stage'),destination=join(root,'channel');mkdirSync(directory);mkdirSync(destination);
 const apk=Buffer.from('Synthetic gate fixture; not an installable APK');writeFileSync(join(directory,'test.apk'),apk);
 const manifest={packageName:'co.fallsoft.pocket',versionCode:2,versionName:'synthetic',variants:[{path:'test.apk',size:apk.length,sha256:sha256(apk),signerSha256:'a'.repeat(64)}]};
 const policy={schema:1,qualification:'synthetic',requiredNativeCases:['Synthetic#one']};
 const build={schema:1,state:'complete',productionSourceSha:'b'.repeat(64),productionSourceBefore:'b'.repeat(64),manifestSha:digest(manifest),policySha:digest(policy),startedAt:1,finishedAt:2,exitCode:0,tasks:['testDebugUnitTest','testReleaseUnitTest','lintDebug','lintRelease','assembleDebug','assembleRelease','assembleQualification','assembleQualificationAndroidTest'],unit:{debug:{tests:1,failures:0,errors:0},release:{tests:1,failures:0,errors:0}},qualificationInputSha:'f'.repeat(64),qualificationApkSha:'c'.repeat(64),testApkSha:'d'.repeat(64)};
 const log='Synthetic: .\nOK (1 test)\n';writeFileSync(join(directory,'native-one.log'),log);
 const native=[{schema:1,case:'Synthetic#one',state:'complete',exitCode:0,productionSourceSha:build.productionSourceSha,qualificationInputSha:build.qualificationInputSha,qualificationApkSha:build.qualificationApkSha,installedApkSha:build.qualificationApkSha,testApkSha:build.testApkSha,versionCode:2,startedAt:3,finishedAt:4,avdName:'nextcomp_env_qa',log:'native-one.log',logSha:sha256(Buffer.from(log))}];
 const save=()=>{for(const [name,value]of Object.entries({'manifest.json':manifest,'build.json':build,'policy.json':policy,'native-one.json':native[0]}))writeFileSync(join(directory,name),JSON.stringify(value));};save();writeFileSync(join(destination,'manifest.json'),JSON.stringify({versionCode:1}));
 return {root,directory,destination,manifest,policy,build,native,save};
}
test('Android instrumentation exit zero does not accept failure, crash or multiple summaries',()=>{
 assert.equal(nativeOutputPassed('OK (1 test)\n'),true);
 for(const text of ['FAILURES!!!\nOK (1 test)\n','INSTRUMENTATION_FAILED: crash\n','OK (2 tests)\n','OK (1 test)\nOK (1 test)\n','shortMsg=Process crashed\nOK (1 test)\n'])assert.equal(nativeOutputPassed(text),false);
});
test('complete artifact-bound receipts qualify and publish under version CAS',()=>{
 const f=fixture();try{assert.equal(validateReleaseQualification(f).nativeCases,1);assert.equal(publishQualifiedUpdate({...f,expectedVersion:1,checkOnly:true}).state,'qualified-not-published');assert.equal(JSON.parse(readFileSync(join(f.destination,'manifest.json'))).versionCode,1);assert.equal(publishQualifiedUpdate({...f,expectedVersion:1}).state,'published');assert.equal(JSON.parse(readFileSync(join(f.destination,'manifest.json'))).versionCode,2);assert.throws(()=>publishQualifiedUpdate({...f,expectedVersion:1}),/Channel changed/);}finally{rmSync(f.root,{recursive:true,force:true});}
});
test('failed/stale/incomplete qualification leaves the live channel untouched',()=>{
 const mutations=[f=>f.native[0].exitCode=1,f=>f.native[0].state='running',f=>f.native[0].productionSourceSha='e'.repeat(64),f=>f.native[0].installedApkSha='e'.repeat(64),f=>f.native[0].startedAt=0,f=>f.native[0].finishedAt=NaN,f=>f.build.finishedAt=NaN,f=>f.build.tasks.pop(),f=>f.build.unit.debug.failures=1,f=>f.policy.requiredNativeCases.push('Synthetic#missing'),f=>f.manifest.variants[0].size++,f=>writeFileSync(join(f.directory,'test.apk'),'Changed'),f=>writeFileSync(join(f.directory,'native-one.log'),'FAILURES!!!\n')];
 for(const mutate of mutations){const f=fixture();try{const before=readFileSync(join(f.destination,'manifest.json'));mutate(f);f.save();assert.throws(()=>publishQualifiedUpdate({...f,expectedVersion:1}));assert.deepEqual(readFileSync(join(f.destination,'manifest.json')),before);}finally{rmSync(f.root,{recursive:true,force:true});}}
});
test('artifact/log escapes and duplicate receipts cannot qualify',()=>{
 const f=fixture();try{writeFileSync(join(f.root,'outside.log'),'OK (1 test)\n');symlinkSync(join(f.root,'outside.log'),join(f.directory,'escape.log'));f.native[0].log='escape.log';assert.throws(()=>validateReleaseQualification(f),/escapes/);f.native[0].log='native-one.log';f.native.push({...f.native[0]});assert.throws(()=>validateReleaseQualification(f),/Duplicate/);}finally{rmSync(f.root,{recursive:true,force:true});}
});

test('release executables parse before any build, emulator or publication effect',()=>{
 for(const file of ['scripts/build-release.mjs','scripts/release-qualification.mjs','scripts/record-native-qualification.mjs','scripts/publish-qualified-update.mjs','scripts/prepare-app-update.mjs']){
  const r=spawnSync(process.execPath,['--check',file],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,file+': '+r.stderr);
 }
});


test('green native logs cannot publish a missing or rejected optimized visual review',()=>{
 for(const rejected of ['missing','unreadable-identities','wrong-apk','changed-capture']){
  const f=fixture();try{
   f.policy.requiredOptimizedReview='native-search-and-team-identity-and-routing';f.build.policySha=digest(f.policy);f.save();
   const image=Buffer.from('Synthetic capture binding; no visual qualification claim');
   for(const name of ['search.png','team.png'])writeFileSync(join(f.directory,name),image);
   const review={schema:1,state:'complete',kind:f.policy.requiredOptimizedReview,versionCode:2,productionSourceSha:f.build.productionSourceSha,qualificationInputSha:f.build.qualificationInputSha,apkSha256:f.manifest.variants[0].sha256,reviewedAt:5,observations:{searchSurvived:true,selectedIdentitiesReadable:true,recipientDraftsPreserved:true,correctSingleSend:true},artifacts:['search.png','team.png'].map(path=>({path,sha256:sha256(image)}))};
   assert.equal(validateReleaseQualification({...f,optimizedReview:review}).nativeCases,1);
   if(rejected!=='missing'){
    if(rejected==='unreadable-identities')review.observations.selectedIdentitiesReadable=false;
    if(rejected==='wrong-apk')review.apkSha256='e'.repeat(64);
    if(rejected==='changed-capture')writeFileSync(join(f.directory,'team.png'),'Changed');
    writeFileSync(join(f.directory,'optimized-review.json'),JSON.stringify(review));
   }
   const before=readFileSync(join(f.destination,'manifest.json'));
   assert.throws(()=>publishQualifiedUpdate({...f,expectedVersion:1}));
   assert.deepEqual(readFileSync(join(f.destination,'manifest.json')),before);
  }finally{rmSync(f.root,{recursive:true,force:true});}
 }
});
