import {spawnSync} from 'node:child_process';
import {readFileSync,readdirSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {validateReleaseQualification,productionSourceInputs,sha256} from './release-qualification.mjs';
const invariants=[
 'co.fallsoft.pocket.NativeCanonicalTranscriptNativeTest#nativeOrderSurvivesDelayedArrivalQuestionsAndReconnectWithoutLegacyRepair',
 'co.fallsoft.pocket.HollywoodMixedCatalogNativeTest#acceptedConversationSendKeepsKeyboardAndNewDraft'
];
const roomFiles=new Set(['FleetRoomProjection','HollywoodRoomActivity','HollywoodRoomCard'].map(n=>'android/app/src/main/java/co/fallsoft/pocket/'+n+'.kt'));
const unitFiles=new Set(['FleetRoomProjectionTest','HollywoodRoomActivityTest'].map(n=>'android/app/src/test/java/co/fallsoft/pocket/'+n+'.kt'));
const models=['co.fallsoft.pocket.FleetRoomProjectionTest','co.fallsoft.pocket.HollywoodRoomActivityTest'];
const git=(root,args,input)=>{
 const r=spawnSync('git',['-C',root,...args],{input,maxBuffer:256*1024*1024});
 if(r.status!==0)throw Error('Cannot verify native test baseline: '+args[0]);return r.stdout;
};
export function gitProductionSourceSha(root,ref){
 const rows=git(root,['ls-tree','-r','-z',ref,'--',...productionSourceInputs]).toString().split('\0').filter(Boolean).map(line=>{
  const match=/^(100644|100755) blob ([0-9a-f]{40,64})\t(.+)$/.exec(line);if(!match)throw Error('Native baseline contains an unsupported source entry');return {oid:match[2],path:match[3]};
 }).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
 if(!rows.length)throw Error('Empty native baseline');
 const bytes=git(root,['cat-file','--batch'],rows.map(r=>r.oid+'\n').join(''));let offset=0;const hash=createHash('sha256');
 for(const row of rows){const end=bytes.indexOf(10,offset);const header=bytes.subarray(offset,end).toString().split(' ');const size=Number(header[2]);if(end<0||header[0]!==row.oid||header[1]!=='blob'||!Number.isSafeInteger(size)||size<0||end+1+size>=bytes.length)throw Error('Native baseline object stream is invalid');const data=bytes.subarray(end+1,end+1+size);hash.update(row.path);hash.update('\0');hash.update(sha256(data));hash.update('\0');offset=end+size+2;}
 if(offset!==bytes.length)throw Error('Native baseline object stream has trailing data');return hash.digest('hex');
}
export function selectNativePolicy(full,{changedFiles=[],baselineVerified=false,versionOnly=false}={}){
 const fallback=reason=>({policy:structuredClone(full),plan:{schema:1,profile:'full-regression',reason,changedFiles}});
 if(!baselineVerified)return fallback('No verified qualified baseline');
 const android=changedFiles.filter(p=>p.startsWith('android/'));
 if(!android.some(p=>roomFiles.has(p)))return fallback('No isolated room-status production change');
 const pipeline=changedFiles.filter(p=>p.startsWith('server/')||p.startsWith('scripts/')&&/release|qualification|native-test-plan/.test(p));
 const unknown=android.filter(p=>!roomFiles.has(p)&&!unitFiles.has(p)&&!(p==='android/app/build.gradle.kts'&&versionOnly));
 if(unknown.length||pipeline.length)return fallback('Cross-cutting or unmapped changes require full regression');
 if(invariants.some(c=>!full.requiredNativeCases.includes(c)))throw Error('Release invariants missing from full policy');
 const policy={...structuredClone(full),qualification:'room-status-change-scoped/v1',requiredNativeCases:[...invariants],unitFilters:models,
  requiredOptimizedObservations:['nativeRoomListStatusDrawn'],limits:['Known room-status components only; two declared model families and two core integration invariants.','An exact optimized-APK room-list status observation is mandatory.','Provider audio is not run or certified by this scoped plan. Physical phone behavior remains separate.','Full regression policy is retained separately; unmapped and cross-cutting native changes cannot select this profile.']};
 return {policy,plan:{schema:1,profile:'room-status',reason:'Known status components changed; original baseline verified',changedFiles,unitFilters:models,providerAudioRequired:false}};
}
export function nativeTestPlan(root,full,{baseRef,baseStage}={}){
 if(!baseRef&&!baseStage)return selectNativePolicy(full);
 if(!baseRef||!baseStage)throw Error('Provide both native baseline ref and its qualified stage');
 const ref=git(root,['rev-parse','--verify',baseRef+'^{commit}']).toString().trim();
 git(root,['merge-base','--is-ancestor',ref,'HEAD']);
 const read=n=>JSON.parse(readFileSync(join(baseStage,n),'utf8'));
 const manifest=read('manifest.json'),build=read('build.json'),policy=read('policy.json');
 validateReleaseQualification({directory:baseStage,manifest,build,policy,native:readdirSync(baseStage).filter(n=>/^native-.+\.json$/.test(n)).map(read),optimizedReview:policy.requiredOptimizedReview?read('optimized-review.json'):undefined});
 if(gitProductionSourceSha(root,ref)!==build.productionSourceSha)throw Error('Qualified native baseline does not match the supplied Git source');
 const changedFiles=[...new Set([...git(root,['diff','--name-only','-z',ref,'--']).toString().split('\0'),...git(root,['ls-files','--others','--exclude-standard','-z','--','android','scripts']).toString().split('\0')].filter(Boolean))].sort();
 const normalize=s=>s.replace(/\bversionCode\s*=\s*\d+/g,'versionCode=#').replace(/\bversionName\s*=\s*"[^"]*"/g,'versionName="#"');
 const gradle='android/app/build.gradle.kts';const versionOnly=changedFiles.includes(gradle)&&normalize(git(root,['show',ref+':'+gradle]).toString())===normalize(readFileSync(join(root,gradle),'utf8'));
 const selected=selectNativePolicy(full,{changedFiles,baselineVerified:true,versionOnly});selected.plan.baseCommit=ref;selected.plan.baseProductionSourceSha=build.productionSourceSha;selected.plan.baseVersionCode=manifest.versionCode;return selected;
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const root=fileURLToPath(new URL('..',import.meta.url));
 const selected=nativeTestPlan(root,JSON.parse(readFileSync(join(root,'scripts/release-policy.json'),'utf8')),{baseRef:process.env.NEXTCOMP_RELEASE_BASE_REF,baseStage:process.env.NEXTCOMP_RELEASE_BASE_STAGE});
 console.log(JSON.stringify({...selected.plan,requiredNativeCases:selected.policy.requiredNativeCases},null,2));
}
