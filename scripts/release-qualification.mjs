import {createHash} from 'node:crypto';
import {readFileSync,readdirSync,lstatSync,realpathSync,statSync} from 'node:fs';
import {resolve,relative,isAbsolute,join} from 'node:path';
const hex=/^[a-f0-9]{64}$/;
const fail=message=>{throw Error(message);};
export const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
export const fileSha=path=>sha256(readFileSync(path));
function inputSha(root,inputs){
 const files=[];const walk=path=>{let s;try{s=lstatSync(path);}catch(e){if(e.code==='ENOENT')return;throw e;}if(s.isDirectory())for(const name of readdirSync(path).sort())walk(join(path,name));else if(s.isFile())files.push(path);else fail('Source fingerprint requires regular files, not symlinks.');};
 inputs.forEach(name=>walk(resolve(root,name)));if(!files.length)fail('No source inputs.');
 const hash=createHash('sha256');for(const path of files.sort()){hash.update(relative(root,path));hash.update('\0');hash.update(fileSha(path));hash.update('\0');}return hash.digest('hex');
}
export const qualificationInputSha=root=>inputSha(root,['android/app/src/test','android/app/src/androidTest','tests/fixtures','server/timeline.mjs']);
export function productionSourceSha(root){
 const inputs=['android/app/src/main','android/app/src/debug','android/app/src/release','android/app/src/qualification','android/app/build.gradle.kts','android/build.gradle.kts','android/settings.gradle.kts','android/gradle.properties','android/gradle'];
 return inputSha(root,inputs);
}
export function nativeOutputPassed(output,expectedTests=1){
 const successes=output.split(/\r?\n/).map(line=>line.trim().match(/^OK \((\d+) tests?\)$/)).filter(Boolean);
 return successes.length===1&&Number(successes[0][1])===expectedTests&&!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|There (?:was|were) \d+ failures?|Error in [\w$]/i.test(output);
}
export function confinedFile(directory,name){
 if(typeof name!=='string'||!name||isAbsolute(name)||name.includes('\0'))fail('Invalid qualification path.');
 const root=realpathSync(directory),path=realpathSync(resolve(root,name)),rel=relative(root,path);
 if((rel==='..'||rel.startsWith('../'))||isAbsolute(rel)||!statSync(path).isFile())fail('Qualification file escapes artifact directory.');return path;
}
export function validateReleaseQualification({directory,manifest,build,native,policy}){
 if(manifest.packageName!=='co.fallsoft.pocket'||!Number.isSafeInteger(manifest.versionCode)||manifest.versionCode<1||!Array.isArray(manifest.variants)||!manifest.variants.length)fail('Invalid prepared manifest.');
 if(build.schema!==1||build.state!=='complete'||!hex.test(build.productionSourceSha)||build.productionSourceBefore!==build.productionSourceSha||build.manifestSha!==sha256(Buffer.from(JSON.stringify(manifest))))fail('Missing, stale or incomplete build qualification.');
 const tasks=['testDebugUnitTest','testReleaseUnitTest','lintDebug','lintRelease','assembleDebug','assembleRelease','assembleQualification','assembleQualificationAndroidTest'];
 if(!Number.isFinite(build.startedAt)||!Number.isFinite(build.finishedAt)||build.finishedAt<build.startedAt)fail('Invalid build timestamps.');
 if(build.exitCode!==0||tasks.some(task=>!build.tasks?.includes(task)))fail('Required build tasks did not complete.');
 for(const variant of ['debug','release']){const unit=build.unit?.[variant];if(!unit||!Number.isSafeInteger(unit.tests)||unit.tests<1||unit.failures!==0||unit.errors!==0)fail('Unit result is unavailable or failed.');}
 if(!hex.test(build.qualificationInputSha)||!hex.test(build.qualificationApkSha)||!hex.test(build.testApkSha))fail('Native APK identities missing.');
 const signers=new Set();for(const v of manifest.variants){if(!hex.test(v.signerSha256)||!hex.test(v.sha256)||signers.has(v.signerSha256)||!Number.isSafeInteger(v.size)||v.size<1||v.size>80*1024*1024)fail('Invalid update signing variant.');signers.add(v.signerSha256);const path=confinedFile(directory,v.path);if(statSync(path).size!==v.size||fileSha(path)!==v.sha256)fail('Prepared APK changed after build.');}
 if(!Array.isArray(native))fail('Native receipts missing.');
 if(policy.schema!==1||!Array.isArray(policy.requiredNativeCases)||!policy.requiredNativeCases.length||new Set(policy.requiredNativeCases).size!==policy.requiredNativeCases.length)fail('Invalid native release policy.');
 if(build.policySha!==sha256(Buffer.from(JSON.stringify(policy))))fail('Release policy changed after build.');
 const byCase=new Map();for(const receipt of native){
  if(typeof receipt.case!=='string'||!policy.requiredNativeCases.includes(receipt.case)||receipt.schema!==1||receipt.state!=='complete'||receipt.exitCode!==0||receipt.productionSourceSha!==build.productionSourceSha||receipt.qualificationInputSha!==build.qualificationInputSha||receipt.qualificationApkSha!==build.qualificationApkSha||receipt.installedApkSha!==build.qualificationApkSha||receipt.testApkSha!==build.testApkSha||receipt.versionCode!==manifest.versionCode||receipt.finishedAt<receipt.startedAt||receipt.startedAt<build.finishedAt||!Number.isFinite(receipt.startedAt)||!Number.isFinite(receipt.finishedAt)||receipt.avdName!=='nextcomp_env_qa')fail('Native receipt is stale, incomplete or for another APK.');
  const log=confinedFile(directory,receipt.log);if(fileSha(log)!==receipt.logSha||!nativeOutputPassed(readFileSync(log,'utf8')))fail('Native test failed or its log changed.');
  if(byCase.has(receipt.case))fail('Duplicate native receipt.');byCase.set(receipt.case,receipt);
 }
 for(const name of policy.requiredNativeCases)if(!byCase.has(name))fail('Required native case missing: '+name);
 return {versionCode:manifest.versionCode,productionSourceSha:build.productionSourceSha,qualification:policy.qualification,nativeCases:policy.requiredNativeCases.length};
}
