import {spawn,spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,mkdirSync,rmSync,symlinkSync,chmodSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {sha256,nativeOutputPassed,validateReleaseQualification,productionSourceSha,qualificationInputSha} from '../scripts/release-qualification.mjs';
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
 for(const file of ['scripts/native-test-plan.mjs','scripts/build-release.mjs','scripts/release-qualification.mjs','scripts/record-native-qualification.mjs','scripts/publish-qualified-update.mjs','scripts/prepare-app-update.mjs']){
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

test('optimized catalog/input review requires its actual observations',()=>{
 const f=fixture();try{
  f.policy.requiredOptimizedReview='native-room-catalog-and-typing';f.build.policySha=digest(f.policy);
  const data=Buffer.from('Synthetic binding only');for(const name of ['input.json','room.png'])writeFileSync(join(f.directory,name),data);
  const review={schema:1,state:'complete',kind:f.policy.requiredOptimizedReview,versionCode:2,productionSourceSha:f.build.productionSourceSha,qualificationInputSha:f.build.qualificationInputSha,apkSha256:f.manifest.variants[0].sha256,reviewedAt:5,observations:{typingDrawn:true,canonicalRoomSnapshot:true,originalAcceptedMessageSingle:true,optimizedPayloadVerified:true},artifacts:['input.json','room.png'].map(path=>({path,sha256:sha256(data)}))};
  assert.equal(validateReleaseQualification({...f,optimizedReview:review}).nativeCases,1);
  review.observations.optimizedPayloadVerified=false;assert.throws(()=>validateReleaseQualification({...f,optimizedReview:review}),/review did not pass/);
 }finally{rmSync(f.root,{recursive:true,force:true});}
});

test('native room voice requires evidence for its declared input interaction',()=>{
 for(const mode of [undefined,'volume-buttons']){
 const f=fixture();try{
  const name='co.fallsoft.pocket.HollywoodVoiceKeysNativeTest#'+(mode?'nativeVolumeKeysKeepOriginalRoomAndDraftWhileProviderIsUnavailable':'nativeVolumeAndTouchKeepOriginalRoomAndDraftWhileProviderIsUnavailable');
  f.policy.requiredNativeCases=[name];f.native[0].case=name;f.policy.requiredOptimizedReview='native-room-catalog-and-typing';
  if(mode)f.policy.captureInteraction=mode;
  f.build.policySha=digest(f.policy);
  const sample=Buffer.from('Synthetic review artifact');
  for(const file of ['input.json','native.png'])writeFileSync(join(f.directory,file),sample);
  const review={schema:1,state:'complete',kind:f.policy.requiredOptimizedReview,versionCode:2,productionSourceSha:f.build.productionSourceSha,qualificationInputSha:f.build.qualificationInputSha,apkSha256:f.manifest.variants[0].sha256,reviewedAt:5,
   observations:{typingDrawn:true,canonicalRoomSnapshot:true,originalAcceptedMessageSingle:true,optimizedPayloadVerified:true,nativeVoiceHardwareKeys:true,nativeVoiceDraftAndOrigin:true},
   artifacts:['input.json','native.png'].map(path=>({path,sha256:sha256(sample)}))};
  review.observations[mode?'nativeVoiceTouch':'nativeVolumeOnlyControls']=true;
  assert.throws(()=>validateReleaseQualification({...f,optimizedReview:review}),/optimized interaction/);
  review.observations[mode?'nativeVolumeOnlyControls':'nativeVoiceTouch']=true;
  assert.equal(validateReleaseQualification({...f,optimizedReview:review}).nativeCases,1);
 }finally{rmSync(f.root,{recursive:true,force:true});}
 }
});

test('a visually rejected candidate cannot publish even with prior green checks',()=>{
 const f=fixture();
 try{
  assert.equal(validateReleaseQualification(f).nativeCases,1);
  writeFileSync(join(f.directory,'visual-rejection.json'),JSON.stringify({state:'rejected',reason:'Observed native clipping'}));
  assert.throws(()=>validateReleaseQualification(f),/visually rejected/);
  assert.throws(()=>publishQualifiedUpdate({...f,expectedVersion:1,checkOnly:true}),/visually rejected/);
  assert.equal(JSON.parse(readFileSync(join(f.destination,'manifest.json'))).versionCode,1);
 }finally{rmSync(f.root,{recursive:true,force:true});}
});

// A custom retained AVD needs the runner's live process/installation preflight;
// changing only the name cannot make an arbitrary target a qualified emulator.
test('explicit owned emulator receipts require retained runtime preflight',()=>{
 const f=fixture();try{
  const receipt=f.native[0];receipt.avdName='nextcomp_federation_20261030';
  assert.throws(()=>validateReleaseQualification(f),/Native receipt/);
  Object.assign(receipt,{serial:'emulator-5650',actor:'01a0f2bc-ff5a-70c2-b682-10f0987dab80',preflight:{emulatorStartTicks:'1058124440',installedApkVerified:true}});
  assert.equal(validateReleaseQualification(f).nativeCases,1);
  receipt.serial='100.101.220.29:5555';assert.throws(()=>validateReleaseQualification(f),/Native receipt/);
 }finally{rmSync(f.root,{recursive:true,force:true});}
});

test('room-status plans keep core integration and exact UI review; unmapped, voice and unverified changes keep full regression',async()=>{
 const {selectNativePolicy}=await import('../scripts/native-test-plan.mjs');
 const current=JSON.parse(readFileSync('scripts/release-policy.json','utf8'));
 assert.equal(selectNativePolicy(current,{changedFiles:['android/app/src/main/java/co/fallsoft/pocket/HollywoodRoomActivity.kt'],baselineVerified:true}).plan.profile,'full-regression');
 const full={...current,requiredOptimizedReview:'native-room-catalog-and-typing',requiredNativeCases:[...current.requiredNativeCases,'co.fallsoft.pocket.HollywoodMixedCatalogNativeTest#acceptedConversationSendKeepsKeyboardAndNewDraft']};
 const status='android/app/src/main/java/co/fallsoft/pocket/HollywoodRoomActivity.kt';
 const scoped=selectNativePolicy(full,{changedFiles:[status,'android/app/build.gradle.kts'],baselineVerified:true,versionOnly:true});
 assert.equal(scoped.plan.profile,'room-status');assert.equal(scoped.policy.requiredNativeCases.length,2);
 assert.ok(scoped.policy.requiredNativeCases.every(c=>full.requiredNativeCases.includes(c)));
 assert.ok(scoped.policy.requiredNativeCases.every(c=>!c.includes('AudioLiveTest')));
 assert.deepEqual(scoped.policy.requiredOptimizedObservations,['nativeRoomListStatusDrawn']);
 for(const input of [{changedFiles:[status]},
  {changedFiles:[status,'android/app/src/main/java/co/fallsoft/pocket/HollywoodScreen.kt'],baselineVerified:true},
  {changedFiles:[status,'android/app/build.gradle.kts'],baselineVerified:true},
  {changedFiles:[status,'scripts/build-release.mjs'],baselineVerified:true}]){
  const x=selectNativePolicy(full,input);assert.equal(x.plan.profile,'full-regression');assert.deepEqual(x.policy.requiredNativeCases,full.requiredNativeCases);
 }
 assert.notEqual(scoped.policy,full);assert.notEqual(scoped.policy.requiredNativeCases,full.requiredNativeCases);
});

test('scoped status publication still rejects missing exact-APK room-list observation',()=>{
 const f=fixture();try{
  f.policy.requiredOptimizedReview='native-room-catalog-and-typing';f.policy.requiredOptimizedObservations=['nativeRoomListStatusDrawn'];f.build.policySha=digest(f.policy);f.save();
  const capture=Buffer.from('Synthetic gate artifact; no rendering proof');for(const name of ['typing.png','status.png'])writeFileSync(join(f.directory,name),capture);
  const review={schema:1,state:'complete',kind:f.policy.requiredOptimizedReview,versionCode:2,productionSourceSha:f.build.productionSourceSha,qualificationInputSha:f.build.qualificationInputSha,apkSha256:f.manifest.variants[0].sha256,reviewedAt:5,observations:{typingDrawn:true,canonicalRoomSnapshot:true,originalAcceptedMessageSingle:true,optimizedPayloadVerified:true},artifacts:['typing.png','status.png'].map(path=>({path,sha256:sha256(capture)}))};
  assert.throws(()=>validateReleaseQualification({...f,optimizedReview:review}),/review did not pass/);
  review.observations.nativeRoomListStatusDrawn=true;assert.equal(validateReleaseQualification({...f,optimizedReview:review}).nativeCases,1);
 }finally{rmSync(f.root,{recursive:true,force:true});}
});

// Execute the actual admission CLI against an inert process owner and fake adb.
// The command transcript proves rejection happens before even force-stop, not
// merely that a helper recognizes text. No emulator or Android SDK is used.
test('native admission retains existing Android error dialogs and prevents fixture/test setup',async()=>{
 const root=fileURLToPath(new URL('..',import.meta.url)),avdName='nextcomp_admission_qa',serial='emulator-5688';
 const processOwner=spawn(process.execPath,['-e','process.stdin.resume()',avdName,serial.slice(9)],{stdio:['pipe','ignore','ignore']});
 await once(processOwner,'spawn');
 const directory=mkdtempSync(join(tmpdir(),'nextcomp-native-admission-'));
 try{
  const emulatorStartTicks=readFileSync('/proc/'+processOwner.pid+'/stat','utf8').split(') ').at(-1).split(' ')[19];
  const build={state:'complete',productionSourceSha:productionSourceSha(root),qualificationInputSha:qualificationInputSha(root),qualificationApkSha:'a'.repeat(64),testApkSha:'b'.repeat(64)};
  // Resource IDs from the retained 175 System UI overlay; unrelated launcher
  // content remains a positive control, including the phrase in ordinary text.
  const samples=[
   {name:'system-anr',xml:'<hierarchy><node package="android" resource-id="android:id/alertTitle" text="System UI isn’t responding"/><node package="android" resource-id="android:id/aerr_close"/><node package="android" resource-id="android:id/aerr_wait"/></hierarchy>',state:'blocked'},
   {name:'app-crash',xml:'<hierarchy><node package="android" resource-id="android:id/aerr_restart" text="Open app again"/></hierarchy>',state:'blocked'},
   {name:'unreadable',xml:'ERROR: could not get idle state.',state:'failed'},
   {name:'clear',xml:'<hierarchy><node package="com.google.android.apps.nexuslauncher" text="System UI isn’t responding — retained note"/></hierarchy>',state:'complete'}
  ];
  for(const sample of samples){
   const stage=join(directory,sample.name),sdk=join(stage,'sdk'),calls=join(stage,'calls.jsonl'),ownerPath=join(stage,'owner.json');
   mkdirSync(join(sdk,'platform-tools'),{recursive:true});
   const caseName='Synthetic#one',id=sha256(Buffer.from(caseName)).slice(0,20);
   for(const [file,value]of Object.entries({'build.json':build,'policy.json':{requiredNativeCases:[caseName]},'manifest.json':{versionCode:175},'owner.json':{owner:'admission-test',serial,avdName,emulatorPid:processOwner.pid,emulatorStartTicks}}))writeFileSync(join(stage,file),JSON.stringify(value));
   const adb=join(sdk,'platform-tools','adb');
   writeFileSync(adb,`#!/usr/bin/env node
const {appendFileSync}=require('node:fs');const args=process.argv.slice(4);
appendFileSync(${JSON.stringify(calls)},JSON.stringify(args)+'\\n');
if(JSON.stringify(args)===JSON.stringify(['shell','getprop','ro.boot.qemu.avd_name'])){process.stdout.write(${JSON.stringify(avdName)});}
else if(JSON.stringify(args)===JSON.stringify(['exec-out','uiautomator','dump','/proc/self/fd/1'])){process.stdout.write(${JSON.stringify(sample.xml)});}
else{process.stderr.write('ADMISSION_EFFECT_REACHED');process.exitCode=91;}
`);chmodSync(adb,0o700);
   const result=spawnSync(process.execPath,[join(root,'scripts/record-native-qualification.mjs'),stage,caseName],{env:{...process.env,ANDROID_HOME:sdk,NEXTCOMP_QA_OWNER_RECEIPT:ownerPath,NEXTCOMP_QA_OWNER:'admission-test',XDG_STATE_HOME:join(stage,'state')},encoding:'utf8',timeout:10000});
   assert.equal(result.status,1,sample.name+': '+result.stderr);
   assert.ok(existsSync(join(stage,'native-'+id+'.json')),sample.name+': '+result.stderr);
   const receipt=JSON.parse(readFileSync(join(stage,'native-'+id+'.json'))),commands=readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse);
   assert.equal(receipt.state,'failed');assert.equal(receipt.admission.state,sample.state);
   assert.equal(readFileSync(join(stage,receipt.admission.hierarchy),'utf8'),sample.xml);
   assert.equal(receipt.admission.hierarchySha,sha256(Buffer.from(sample.xml)));
   const expected=[['shell','getprop','ro.boot.qemu.avd_name'],['exec-out','uiautomator','dump','/proc/self/fd/1']];
   if(sample.state==='complete')expected.push(['shell','am','force-stop','co.fallsoft.pocket']);
   else assert.match(receipt.error,/No fixture or test input was started/);
   assert.deepEqual(commands,expected,sample.name+' must stop at the admission boundary');
   assert.equal(existsSync(join(stage,'fixture-'+id+'.log')),false);
   assert.equal(JSON.parse(readFileSync(ownerPath)).fixturePid,undefined);
   assert.equal(receipt.originalPreferencesSha,undefined);
  }
 }finally{processOwner.kill();await once(processOwner,'exit');rmSync(directory,{recursive:true,force:true});}
});
