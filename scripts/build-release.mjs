import {spawnSync} from 'node:child_process';
import {copyFileSync,mkdirSync,readFileSync,writeFileSync,readdirSync,existsSync,statfsSync,rmSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join,resolve,isAbsolute,relative} from 'node:path';
import {hostname} from 'node:os';
import {productionSourceSha,qualificationInputSha,fileSha,sha256} from './release-qualification.mjs';
const root=fileURLToPath(new URL('..',import.meta.url));
for(const key of ['POCKET_SIGNING_STORE','POCKET_SIGNING_PASSWORD','POCKET_RELEASE_STAGE','NEXTCOMP_BUILD_HOST'])if(!process.env[key])throw Error(`${key} is required; see docs/RELEASING.md.`);
if(hostname()!==process.env.NEXTCOMP_BUILD_HOST)throw Error('Not the explicitly configured build host.');
const outside=path=>{if(!isAbsolute(path))throw Error('Private build paths must be absolute.');const rel=relative(root,resolve(path));if(rel!== '..'&&!rel.startsWith('../'))throw Error('Private build paths must stay outside the checkout.');return resolve(path);};
outside(process.env.POCKET_SIGNING_STORE);
const phoneStore=process.env.POCKET_PHONE_SIGNING_STORE;
if(process.env.POCKET_DEBUG_STORE&&!phoneStore)throw Error('Phone-compatible delivery requires optimized release signing credentials; do not publish the debug APK.');
if(phoneStore){outside(phoneStore);for(const key of ['POCKET_PHONE_SIGNING_PASSWORD','POCKET_PHONE_SIGNING_ALIAS'])if(!process.env[key])throw Error(`${key} is required for the optimized compatible release.`);}

const stage=outside(process.env.POCKET_RELEASE_STAGE);if(existsSync(stage))throw Error('Use a new staging directory; inspect an existing build instead of overwriting its receipt.');
const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT;if(!sdk)throw Error('Set ANDROID_HOME.');
mkdirSync(stage,{recursive:true,mode:0o700});const directory=join(stage,'app-updates');mkdirSync(directory,{mode:0o700});
const policy=JSON.parse(readFileSync(join(root,'scripts/release-policy.json'),'utf8'));
const record={schema:1,state:'building',startedAt:Date.now(),productionSourceBefore:productionSourceSha(root),qualificationInputSha:qualificationInputSha(root),policySha:sha256(Buffer.from(JSON.stringify(policy))),tasks:[]};
writeFileSync(join(directory,'build-intent.json'),JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});
const run=(command,args,env=process.env,cwd=root)=>{const disk=statfsSync(stage);if(disk.bavail*disk.bsize<80*1024**3+512*1024**2)throw Error('Build recovery reserve unavailable.');const r=spawnSync(command,args,{cwd,env,stdio:'inherit'});if(r.status!==0)throw Error(`Release command did not complete: ${command} (${r.status??r.signal})`);};
const unit=variant=>{const directory=join(root,'android/app/build/test-results/test'+variant+'UnitTest');const result={tests:0,failures:0,errors:0,skipped:0};for(const name of readdirSync(directory).filter(n=>/^TEST-.+\.xml$/.test(n))){const xml=readFileSync(join(directory,name),'utf8'),header=xml.match(/<testsuite\s[^>]+>/)?.[0];if(!header)throw Error('Invalid JUnit output.');for(const key of Object.keys(result)){const value=header.match(new RegExp('\\b'+key+'="(\\d+)"'))?.[1];if(value===undefined)throw Error('Incomplete JUnit output.');result[key]+=Number(value);}}if(!result.tests||result.failures||result.errors||result.skipped>=result.tests)throw Error('Unit checks failed or unavailable.');return result;};
try{
 // Incremental ZIP packaging can retain large dead gaps after input changes.
 // Rebuild only this checkout's disposable package caches and final APK files.
 // Staged/qualified/published artifacts are outside these fixed paths.
 record.freshPackaging=[];
 const packages=[
  ['packageDebug','debug/app-debug.apk'],
  ['packageRelease','release/app-release.apk'],
  ['packageQualification','qualification/app-qualification.apk'],
  ['packageQualificationAndroidTest','androidTest/qualification/app-qualification-androidTest.apk']
 ];
 for(const [cache,apk]of packages){
  const paths=[join(root,'android/app/build/intermediates/incremental',cache),join(root,'android/app/build/outputs/apk',apk)];
  paths.forEach(path=>rmSync(path,{recursive:true,force:true}));
  record.freshPackaging.push({cache,apk});
 }
 writeFileSync(join(directory,'build-intent.json'),JSON.stringify(record,null,2)+'\n',{mode:0o600});
 const signed=['testDebugUnitTest','testReleaseUnitTest','lintDebug','lintRelease','assembleDebug','assembleRelease'];
 run('./gradlew',['--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8',...signed],process.env,join(root,'android'));record.tasks.push(...signed);record.unit={debug:unit('Debug'),release:unit('Release')};
 const primaryRelease=join(stage,'primary-release.apk');
 copyFileSync(join(root,'android/app/build/outputs/apk/release/app-release.apk'),primaryRelease);
 let compatibleRelease=join(root,'android/app/build/outputs/apk/debug/app-debug.apk');
 if(phoneStore){
  const phoneEnv={...process.env,POCKET_SIGNING_STORE:phoneStore,POCKET_SIGNING_PASSWORD:process.env.POCKET_PHONE_SIGNING_PASSWORD,POCKET_SIGNING_ALIAS:process.env.POCKET_PHONE_SIGNING_ALIAS};
  delete phoneEnv.POCKET_SIGNING_KEY_PASSWORD;
  run('./gradlew',['--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8','assembleRelease'],phoneEnv,join(root,'android'));
  compatibleRelease=join(stage,'phone-compatible-release.apk');
  copyFileSync(join(root,'android/app/build/outputs/apk/release/app-release.apk'),compatibleRelease);
  record.tasks.push('assembleRelease(phone-compatible-optimized)');
  record.phoneCompatible={optimized:true,apkSha256:fileSha(compatibleRelease)};
 }
 const qaEnv={...process.env};delete qaEnv.POCKET_DEBUG_STORE;
 const qa=['assembleQualification','assembleQualificationAndroidTest'];run('./gradlew',['--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8',...qa],qaEnv,join(root,'android'));record.tasks.push(...qa);
 const env={...process.env,POCKET_DATA:stage};delete env.POCKET_SIGNING_PASSWORD;delete env.POCKET_SIGNING_KEY_PASSWORD;
 const release=primaryRelease;
 run(process.execPath,[join(root,'scripts/prepare-app-update.mjs'),release,compatibleRelease],env);
 const manifest=JSON.parse(readFileSync(join(directory,'manifest.json'),'utf8'));
 record.productionSourceSha=productionSourceSha(root);if(record.productionSourceSha!==record.productionSourceBefore||qualificationInputSha(root)!==record.qualificationInputSha)throw Error('Source changed during build.');
 if(!/^[a-zA-Z0-9._-]+$/.test(manifest.versionName))throw Error('Invalid APK version name.');
 copyFileSync(join(root,'android/app/build/outputs/apk/qualification/app-qualification.apk'),join(directory,'qualification.apk'));
 copyFileSync(join(root,'android/app/build/outputs/apk/androidTest/qualification/app-qualification-androidTest.apk'),join(directory,'qualification-test.apk'));
 record.qualificationApkSha=fileSha(join(directory,'qualification.apk'));record.testApkSha=fileSha(join(directory,'qualification-test.apk'));record.manifestSha=sha256(Buffer.from(JSON.stringify(manifest)));
 writeFileSync(join(directory,'policy.json'),JSON.stringify(policy,null,2)+'\n',{mode:0o600});
 const dist=join(root,'dist');mkdirSync(dist,{recursive:true});const name=`nextcomp-${manifest.versionName}.apk`;copyFileSync(release,join(dist,name));writeFileSync(join(dist,'SHA256SUMS.txt'),fileSha(release)+'  '+name+'\n');
 const cert=spawnSync(join(sdk,'build-tools/36.0.0/apksigner'),['verify','--verbose','--print-certs',release],{encoding:'utf8',env});if(cert.status!==0)throw Error('APK signature verification failed.');writeFileSync(join(dist,'SIGNING-CERTIFICATE.txt'),cert.stdout);
 record.exitCode=0;record.state='complete';record.finishedAt=Date.now();writeFileSync(join(directory,'build.json'),JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});console.log(JSON.stringify({state:'built-not-published',versionCode:manifest.versionCode,unit:record.unit}));
}catch(error){record.state='failed';record.finishedAt=Date.now();writeFileSync(join(directory,'build-failed.json'),JSON.stringify(record,null,2)+'\n',{mode:0o600});throw error;}
