import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
// Scan exactly the tracked/staged publication set, never private runtime files.
const files=execFileSync('git',['ls-files','-z'],{encoding:'utf8'}).split('\0').filter(Boolean);
if(!files.length)throw Error('No tracked source files to check. Stage the intended publication first.');
const blocked=/(^|\/)(data|review|node_modules|\.gradle|build|dist)\/|(^|\/)(local\.properties|google-services\.json|signing\.properties|\.env)$|\.(jks|keystore|pem|key|apk|sqlite)(-|$)/;
const secrets=[/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,/"private_key"\s*:\s*"/,/\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b/,/\btail[a-f0-9]{6}\.ts\.net\b/,/\/home\/ai\//];
const failures=[];
for(const path of files){
  if(blocked.test(path)){failures.push(path+': private/build path');continue;}
  const data=readFileSync(path);if(data.includes(0))continue;
  if(secrets.some(re=>re.test(data.toString())))failures.push(path+': private credential or local deployment reference');
}
if(failures.length){console.error(failures.join('\n'));process.exitCode=1;}
else console.log(`Checked ${files.length} tracked files: no prohibited paths, key markers, or local deployment references.`);
