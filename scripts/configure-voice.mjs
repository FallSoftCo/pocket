// The key stays in the server's private data directory, never the APK or Git.
import {mkdirSync,writeFileSync,chmodSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline';
const dir=process.env.POCKET_DATA||fileURLToPath(new URL('../data/',import.meta.url));
let key=process.env.POCKET_VOICE_API_KEY;
if(!key){if(process.stdin.isTTY){console.error('Paste the API key, then Enter (input hidden):');process.stdin.setRawMode(true);key=await new Promise(resolve=>{let s='';process.stdin.on('data',b=>{for(const c of b.toString()){if(c==='\u0003')process.exit(130);if(c==='\r'||c==='\n'){process.stdin.setRawMode(false);process.stdin.pause();resolve(s);return;}if(c==='\u007f')s=s.slice(0,-1);else s+=c;}});});}else{const lines=createInterface({input:process.stdin});for await(const line of lines){key=line.trim();break;}}}
if(!key||!key.startsWith('sk-'))throw Error('Supply a valid OpenAI API key.');
const r=await fetch('https://api.openai.com/v1/models',{headers:{Authorization:`Bearer ${key}`},signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error(`Key validation failed: HTTP ${r.status}`);
mkdirSync(dir,{recursive:true,mode:0o700});const path=resolve(dir,'voice-key');writeFileSync(path,key.trim(),{mode:0o600});chmodSync(path,0o600);console.log('Voice key saved privately. Restart the Pocket server to enable voice.');
