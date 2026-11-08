/** Local node-reader file protection. Never relax ACLs or expose key material. */
import {execFileSync} from 'node:child_process';
import {constants,openSync,closeSync,fstatSync,readFileSync,lstatSync,fsyncSync} from 'node:fs';
import {dirname} from 'node:path';

function privateAcl(path){
 // Windows POSIX mode bits do not attest access. Inspect actual owner/allow
 // ACEs under the existing identity; no icacls repair or account switching.
 if(process.platform==='win32'){
  const script=`$ErrorActionPreference='Stop'; $p=([Console]::In.ReadToEnd()|ConvertFrom-Json).path;
  $s=Get-Acl -LiteralPath $p; $me=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;
  $allowed=@($me,'S-1-5-18','S-1-5-32-544');
  $owner=$s.GetOwner([Security.Principal.SecurityIdentifier]).Value;
  if($allowed -notcontains $owner){throw 'Owner differs'};
  $rules=@($s.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]));
  if($rules.Count -eq 0){throw 'No usable private ACL'};
  foreach($ace in $rules){
   if($ace.AccessControlType -eq 'Allow' -and $allowed -notcontains $ace.IdentityReference.Value){throw 'Public ACL'}
  }; [Console]::Out.Write('protected')`;
  const result=execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],
   {input:JSON.stringify({path}),encoding:'utf8',timeout:10000,maxBuffer:4096,windowsHide:true});
  if(result!=='protected')throw Error('Reader ACL unavailable.');
 }
}

export function privateNodeDirectory(path){
 const st=lstatSync(path);
 if(!st.isDirectory()||st.isSymbolicLink()||process.platform!=='win32'&&((st.mode&0o077)||![0,process.getuid()].includes(st.uid)))
  throw Error('Reader state must be an existing private owned directory.');
 privateAcl(path);
}

export function privateNodeFile(path,max){
 if(lstatSync(path).isSymbolicLink())throw Error('Reader file is a link.');
 privateAcl(path);
 const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
 try{
  const st=fstatSync(fd);
  if(!st.isFile()||st.size>max||process.platform!=='win32'&&((st.mode&0o077)||![0,process.getuid()].includes(st.uid)))
   throw Error('Reader file is not private.');
  return readFileSync(fd);
 }finally{closeSync(fd);}
}

export function syncReaderDirectory(path){
 // Windows FlushFileBuffers is applied to each written receipt before rename;
 // Node cannot open a directory HANDLE with FILE_FLAG_BACKUP_SEMANTICS.
 // Verification reopens the immutable blob, so a lost receipt is recoverable.
 if(process.platform==='win32')return;
 const fd=openSync(dirname(path),constants.O_RDONLY|constants.O_DIRECTORY);
 try{fsyncSync(fd);}finally{closeSync(fd);}
}
