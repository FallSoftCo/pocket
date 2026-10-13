import {spawn,spawnSync} from 'node:child_process';
import {readFileSync,writeFileSync,existsSync,openSync,closeSync,realpathSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {fileSha,sha256,productionSourceSha,qualificationInputSha,nativeOutputPassed} from './release-qualification.mjs';
const root=fileURLToPath(new URL('..',import.meta.url));
const [stage,name]=process.argv.slice(2),directory=resolve(stage||'.');
const read=path=>JSON.parse(readFileSync(path,'utf8'));
const build=read(join(directory,'build.json')),policy=read(join(directory,'policy.json')),manifest=read(join(directory,'manifest.json'));
if(!policy.requiredNativeCases.includes(name)||!name?.match(/^[a-zA-Z0-9_.$]+#[a-zA-Z0-9_]+$/))throw Error('Select one required native case.');
const current=()=>build.state==='complete'&&productionSourceSha(root)===build.productionSourceSha&&qualificationInputSha(root)===build.qualificationInputSha;
if(!current())throw Error('Build/source qualification is stale.');
const ownerPath=process.env.NEXTCOMP_QA_OWNER_RECEIPT,actor=process.env.NEXTCOMP_QA_OWNER;
if(!ownerPath||!actor)throw Error('Provide the private owned-emulator receipt and actor.');
const owner=read(ownerPath),serial='emulator-5620',sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT;
if(!sdk||owner.owner!==actor||!Number.isSafeInteger(owner.emulatorPid))throw Error('Wrong QA owner or SDK.');
const command=readFileSync('/proc/'+owner.emulatorPid+'/cmdline','utf8');
const ticks=readFileSync('/proc/'+owner.emulatorPid+'/stat','utf8').split(') ').at(-1).split(' ')[19];
if(owner.emulatorStartTicks!==ticks||!command.includes('nextcomp_env_qa')||!command.includes('5620'))throw Error('Owned emulator process is not current.');
const adb=join(sdk,'platform-tools/adb');
const childEnv={PATH:process.env.PATH,HOME:process.env.HOME,LANG:'C.UTF-8'};
const run=(args,timeout=120000)=>{const r=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',env:childEnv,timeout,maxBuffer:4*1024*1024});if(r.status!==0)throw Error('Owned emulator command failed: '+args[0]);return r.stdout;};
if(run(['shell','getprop','ro.boot.qemu.avd_name']).trim()!=='nextcomp_env_qa')throw Error('Wrong native target.');
const id=sha256(Buffer.from(name)).slice(0,20),receiptPath=join(directory,'native-'+id+'.json'),log='native-'+id+'.log';
if(existsSync(receiptPath))throw Error('Native intent already exists; observe it instead of replaying.');
const receipt={schema:1,state:'running',case:name,actor,startedAt:Date.now(),versionCode:manifest.versionCode,productionSourceSha:build.productionSourceSha,qualificationInputSha:build.qualificationInputSha,qualificationApkSha:build.qualificationApkSha,testApkSha:build.testApkSha,avdName:'nextcomp_env_qa',serial,log};
writeFileSync(receiptPath,JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o600});
const persist=()=>writeFileSync(receiptPath,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
// Only reviewed synthetic services can run inside release qualification.
const teamCase=name==='co.fallsoft.pocket.TeamControlWorkflowNativeTest#cyclingSelectorsPreserveScopedDraftsReplyContextAndExactDirectTarget'||name.startsWith('co.fallsoft.pocket.RoomNavigationNativeTest#')||name.startsWith('co.fallsoft.pocket.RoomVoiceNativeTest#');
const environmentCase=name.startsWith('co.fallsoft.pocket.UpdateOriginNativeTest#')||name.startsWith('co.fallsoft.pocket.EnvironmentIsolationNativeTest#');
const fixtureScript=environmentCase?'tests/fixtures/environment-isolation-native.mjs':teamCase?'tests/fixtures/immersion-cycle-team-ui.mjs':'tests/fixtures/transcript-refresh-native.mjs';
const fixturePort=environmentCase?'19995':teamCase?'19992':'19998';
const fixtureToken=teamCase?'synthetic-alpha24-only':'synthetic-system76';
const fixtureHost=environmentCase?'System76':teamCase?'Synthetic alpha24':'Synthetic';
const fixtureReadyMessage=environmentCase?'Synthetic environment ready System76 19995':teamCase?'Synthetic alpha24 fixture19992':'Owned transcript fixture ready';
const ownedFixtures=['tests/fixtures/environment-isolation-native.mjs','tests/fixtures/transcript-refresh-native.mjs','tests/fixtures/immersion-cycle-team-ui.mjs'];
try{
 if(owner.fixturePid&&existsSync('/proc/'+owner.fixturePid)){
  const args=readFileSync('/proc/'+owner.fixturePid+'/cmdline','utf8');
  if(!ownedFixtures.some(script=>args.includes(script))||realpathSync('/proc/'+owner.fixturePid+'/cwd')!==resolve(root))throw Error('Refuse to stop an unrelated fixture process.');
  process.kill(owner.fixturePid,'SIGTERM');for(let n=0;n<60&&existsSync('/proc/'+owner.fixturePid);n++)await pause(50);
  if(existsSync('/proc/'+owner.fixturePid))throw Error('Old owned fixture has not stopped.');
 }
 const fd=openSync(join(directory,'fixture-'+id+'.log'),'wx',0o600);
 const fixture=spawn(process.execPath,[fixtureScript],{cwd:root,env:{...childEnv,PORT:fixturePort,NEXTCOMP_QA_LOG:join(directory,'fixture-'+id+'.jsonl')},stdio:['ignore',fd,fd],detached:true});closeSync(fd);let fixtureError=null;fixture.once('error',e=>fixtureError=e);fixture.unref();owner.fixturePid=fixture.pid;writeFileSync(ownerPath,JSON.stringify(owner,null,2)+'\n');receipt.fixturePid=fixture.pid;persist();
 let ready=false;for(let n=0;n<40;n++){if(fixtureError||fixture.exitCode!==null)throw Error('Fresh fixture exited.');try{const r=await fetch('http://127.0.0.1:'+fixturePort+'/api/status',{headers:{Authorization:'Bearer '+fixtureToken},signal:AbortSignal.timeout(300)});if(r.ok&&(await r.json()).host===fixtureHost&&readFileSync(join(directory,'fixture-'+id+'.log'),'utf8').includes(fixtureReadyMessage)){ready=true;break;}}catch{}await pause(50);}if(!ready)throw Error('Fresh synthetic fixture unavailable.');
 run(['reverse','tcp:18880','tcp:'+fixturePort]);
 if(name.startsWith('co.fallsoft.pocket.EnvironmentIsolationNativeTest#')){run(['reverse','tcp:18881','tcp:19996']);run(['reverse','tcp:18882','tcp:19997']);}
 else if(environmentCase){run(['reverse','tcp:18882','tcp:19996']);run(['reverse','tcp:18883','tcp:19997']);}
 const installedPackageSha=packageName=>{
  const result=spawnSync(adb,['-s',serial,'shell','pm','path',packageName],{encoding:'utf8',env:childEnv,timeout:30000});
  if(result.status!==0)return null;
  const paths=(result.stdout||'').trim().split(/\r?\n/);
  if(paths.length!==1)return null; // Never assume a split installation is identical.
  const path=paths[0].replace(/^package:/,'');
  if(!/^\/data\/app\/[a-zA-Z0-9_~.+/=-]+\/base\.apk$/.test(path))return null;
  return run(['shell','sha256sum',path]).split(/\s/)[0];
 };
 receipt.installation=[];
 for(const [file,sha,packageName]of [
  ['qualification.apk',build.qualificationApkSha,'co.fallsoft.pocket'],
  ['qualification-test.apk',build.testApkSha,'co.fallsoft.pocket.test']
 ]){
  if(fileSha(join(directory,file))!==sha)throw Error('Native APK changed.');
  const reused=installedPackageSha(packageName)===sha;
  if(!reused)run(['install','-r',...(file.includes('test')?['-t']:[]),join(directory,file)]);
  if(installedPackageSha(packageName)!==sha)throw Error('Installed native package identity differs.');
  receipt.installation.push({packageName,sha256:sha,reused});persist();
 }
 const installed=()=>{const path=run(['shell','pm','path','co.fallsoft.pocket']).trim().replace(/^package:/,'');if(!/^\/data\/app\/[a-zA-Z0-9_~.+/=-]+\/base\.apk$/.test(path))throw Error('Unexpected installed APK path.');return run(['shell','sha256sum',path]).split(/\s/)[0];};
 if(!run(['shell','dumpsys','package','co.fallsoft.pocket']).includes('versionCode='+manifest.versionCode+' '))throw Error('Wrong installed version.');
 receipt.installedApkSha=installed();if(receipt.installedApkSha!==build.qualificationApkSha)throw Error('Wrong installed qualification APK.');
 // Preflight is setup evidence, not a product assertion. Only the owned AVD.
 run(['shell','pm','grant','co.fallsoft.pocket','android.permission.POST_NOTIFICATIONS']);
 const permissions=run(['shell','dumpsys','package','co.fallsoft.pocket']);
 if(!/android\.permission\.POST_NOTIFICATIONS:\s*granted=true/.test(permissions))throw Error('Native setup failed: notification permission unavailable.');
 receipt.preflight={state:'complete',notificationPermissionGranted:true,fixtureReady:true,installedApkVerified:true,emulatorStartTicks:ticks};persist();
 const r=spawnSync(adb,['-s',serial,'shell','am','instrument','-w','-e','class',name,'co.fallsoft.pocket.test/androidx.test.runner.AndroidJUnitRunner'],{encoding:'utf8',env:childEnv,timeout:180000,maxBuffer:4*1024*1024});
 const output=(r.stdout||'')+(r.stderr||'');writeFileSync(join(directory,log),output,{flag:'wx',mode:0o600});receipt.logSha=fileSha(join(directory,log));receipt.exitCode=r.status;
 if(r.status!==0||!nativeOutputPassed(output)||!current()||installed()!==build.qualificationApkSha)throw Error('Native qualification failed; channel must not be published.');
 receipt.state='complete';receipt.finishedAt=Date.now();persist();console.log(JSON.stringify({state:'complete',case:name,elapsedMs:receipt.finishedAt-receipt.startedAt}));
}catch(error){receipt.state='failed';receipt.finishedAt=Date.now();receipt.error=error.message;persist();throw error;}
