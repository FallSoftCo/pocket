import {createInterface} from 'node:readline';
import {readFile,mkdir,open,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {receiveTerminalFork} from '../server/terminal-fork-transport.mjs';
const args=new Map();for(let i=2;i<process.argv.length;i+=2)args.set(process.argv[i],process.argv[i+1]);
try{
  const data=args.get('--data'),profile=args.get('--profile'),url=args.get('--url');if(!data?.startsWith('/')||!profile?.startsWith('/'))throw Error('Owner configured data and profile paths are required.');
  const endpoint=new URL(url);if(endpoint.protocol!=='http:'||!['127.0.0.1','localhost','[::1]'].includes(endpoint.hostname))throw Error('Destination control must be loopback.');
  const config=JSON.parse(await readFile(resolve(data,'terminal-forks.json'),'utf8'));if(resolve(config.codeHome)!==resolve(profile))throw Error('Destination profile does not match its owner registration.');
  const token=JSON.parse(await readFile(resolve(data,'secrets.json'),'utf8')).adminToken;
  const request=async(path,options={})=>{const r=await fetch(url+path,{headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...options,...(options.body?{body:JSON.stringify(options.body)}:{}),signal:AbortSignal.timeout(60000)});const d=await r.json();if(!r.ok)throw Error(d.error||'Destination rejected this operation.');return d;};
  const immutableManifest=async(path,text)=>{let existing;try{existing=await readFile(path,'utf8');}catch(e){if(e.code!=='ENOENT')throw e;}if(existing!=null){if(existing!==text)throw Error('This transfer ID already has a different checkpoint.');return;}await writeFile(path,text,{mode:0o600,flag:'wx'});};
  const result=await receiveTerminalFork(createInterface({input:process.stdin}),{directory:id=>resolve(data,'terminal-forks','imports',id),ownerRequest:request,mkdir:path=>mkdir(path,{recursive:true,mode:0o700}),writeFile:immutableManifest,openFile:path=>open(path,'w',0o600)});
  process.stdout.write(JSON.stringify(result));
}catch(e){process.stdout.write(JSON.stringify({error:e.message}));process.exitCode=1;}
