import {spawnSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,copyFileSync,renameSync,statSync,chmodSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';

// APKs remain private to paired devices, including compatible development builds.
const root=fileURLToPath(new URL('..',import.meta.url));
const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT;
if(!sdk)throw Error('Set ANDROID_HOME to verify update APKs.');
const files=process.argv.slice(2);if(!files.length)throw Error('Supply verified build APK paths.');
const out=join(process.env.POCKET_DATA||join(root,'data'),'app-updates');mkdirSync(out,{recursive:true,mode:0o700});chmodSync(out,0o700);
const inspect=(tool,args)=>{const r=spawnSync(join(sdk,'build-tools/36.0.0',tool),args,{encoding:'utf8'});if(r.status!==0)throw Error(`${tool} verification failed.`);return r.stdout;};
const variants=[];let manifest;
for(const source of files){
 const path=resolve(source),certs=inspect('apksigner',['verify','--print-certs',path]);
 const signerSha256=certs.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})/i)?.[1]?.toLowerCase();
 if(!signerSha256||/Signer #2 certificate/.test(certs))throw Error('Require one verified update signing identity.');
 const pkg=inspect('aapt2',['dump','badging',path]).match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
 if(!pkg||pkg[1]!=='co.fallsoft.pocket')throw Error('Unexpected update package.');
 const current={packageName:pkg[1],versionCode:Number(pkg[2]),versionName:pkg[3]};
 if(manifest&&JSON.stringify(current)!==JSON.stringify(manifest))throw Error('Update variants must have the same package and version.');
 manifest=current;
 if(variants.some(v=>v.signerSha256===signerSha256))throw Error('Duplicate signing identity.');
 const sha256=createHash('sha256').update(readFileSync(path)).digest('hex'),name=`nextcomp-${current.versionCode}-${signerSha256.slice(0,12)}.apk`;
 copyFileSync(path,join(out,name));chmodSync(join(out,name),0o600);
 variants.push({signerSha256,path:name,sha256,size:statSync(path).size});
}
writeFileSync(join(out,'manifest.json.tmp'),JSON.stringify({...manifest,variants},null,2)+'\n',{mode:0o600});
renameSync(join(out,'manifest.json.tmp'),join(out,'manifest.json'));
console.log(`Prepared private ${manifest.versionName} update for ${variants.length} verified signing identities.`);
