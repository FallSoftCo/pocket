import {readFileSync,writeFileSync,readdirSync,mkdirSync,openSync,closeSync,unlinkSync,copyFileSync,renameSync,existsSync,fsyncSync,constants} from 'node:fs';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateReleaseQualification,fileSha,sha256,confinedFile} from './release-qualification.mjs';
const digest=value=>sha256(Buffer.from(JSON.stringify(value)));
const read=path=>JSON.parse(readFileSync(path,'utf8'));
const sync=path=>{const fd=openSync(path,'r');try{fsyncSync(fd);}finally{closeSync(fd);}};
/** Trusted operator pipeline: this prevents accidental invalid publication; it
 * cannot fence an administrator writing the channel directly. */
export function publishQualifiedUpdate({directory,destination,expectedVersion,checkOnly=false}){
 directory=resolve(directory);destination=resolve(destination);
 const manifest=read(join(directory,'manifest.json')),build=read(join(directory,'build.json')),policy=read(join(directory,'policy.json'));
 const native=readdirSync(directory).filter(name=>/^native-.+\.json$/.test(name)).map(name=>read(join(directory,name)));
 const qualified=validateReleaseQualification({directory,manifest,build,policy,native});
 const manifestPath=join(destination,'manifest.json');
 const current=()=>existsSync(manifestPath)?read(manifestPath):null;
 const before=current();
 if(!Number.isSafeInteger(expectedVersion)||expectedVersion<0||(before?.versionCode??0)!==expectedVersion||manifest.versionCode<=expectedVersion)throw Error('Channel changed or candidate is not a newer version.');
 if(checkOnly)return {...qualified,state:'qualified-not-published'};
 mkdirSync(destination,{recursive:true,mode:0o700});
 const lock=join(destination,'.qualified-publication.lock'),journal=join(destination,'.qualified-publication-'+manifest.versionCode+'.json');
 const fd=openSync(lock,'wx',0o600);
 try{
  if(existsSync(journal))throw Error('Existing publication intent: reconcile its manifest and artifacts, do not replay.');
  const receipt={schema:1,state:'intent',versionCode:manifest.versionCode,expectedVersion,manifestSha:digest(manifest),qualification:qualified,startedAt:Date.now()};
  writeFileSync(journal,JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o600});sync(journal);sync(destination);
  for(const variant of manifest.variants){
   const target=join(destination,variant.path);
   if(resolve(target)!==join(destination,variant.path)||variant.path.includes('/')||variant.path.includes('\\'))throw Error('Update artifact must be a single filename.');
   if(existsSync(target)){if(fileSha(target)!==variant.sha256)throw Error('Existing artifact conflicts with candidate.');continue;}
   const temporary=target+'.qualified-'+process.pid+'.tmp';
   try{copyFileSync(confinedFile(directory,variant.path),temporary,constants.COPYFILE_EXCL);if(fileSha(temporary)!==variant.sha256)throw Error('Artifact changed during copy.');sync(temporary);renameSync(temporary,target);sync(destination);}finally{if(existsSync(temporary))unlinkSync(temporary);}
  }
  if(digest(current())!==digest(before))throw Error('Channel changed during staging.');
  // Revalidate logs/artifacts after staging and before the one live-channel write.
  validateReleaseQualification({directory,manifest,build,policy,native});
  const temporary=manifestPath+'.qualified-'+process.pid+'.tmp';
  try{writeFileSync(temporary,JSON.stringify(manifest,null,2)+'\n',{mode:0o600,flag:'wx'});sync(temporary);renameSync(temporary,manifestPath);sync(destination);}finally{if(existsSync(temporary))unlinkSync(temporary);}
  receipt.state='published';receipt.finishedAt=Date.now();writeFileSync(journal,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});sync(journal);return {...qualified,state:'published'};
 }finally{closeSync(fd);unlinkSync(lock);}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [directory,destination,version,mode]=process.argv.slice(2);
 if(!directory||!destination||!/^\d+$/.test(version||'')||mode&&!['--check'].includes(mode))throw Error('Usage: publish-qualified-update.mjs STAGE/app-updates DESTINATION EXPECTED_VERSION [--check]');
 console.log(JSON.stringify(publishQualifiedUpdate({directory,destination,expectedVersion:Number(version),checkOnly:mode==='--check'})));
}
