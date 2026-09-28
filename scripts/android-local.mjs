#!/usr/bin/env node
import {appendFileSync,chmodSync,existsSync,mkdirSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const prefix=process.env.PREFIX;
const home=process.env.HOME;
const action=process.argv[2]||'install';
if(!prefix||!home||!prefix.includes('com.termux'))throw Error('Run this command inside Termux on Android.');
const service=join(prefix,'var/service/pocket-local');
const data=join(home,'.local/share/pocket-local');
const log=join(home,'.local/state/pocket-local/log');
const codex=join(prefix,'bin/codex-phone');
if(!existsSync(codex))throw Error('Install and sign in to Codex first; the codex-phone command is missing.');

const request=async(path,body,token)=>{
  const response=await fetch(`http://127.0.0.1:18880${path}`,{method:body?'POST':'GET',headers:{...(token?{Authorization:`Bearer ${token}`} :{}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});
  const value=await response.json();if(!response.ok)throw Error(value.error||`Pocket returned HTTP ${response.status}`);return value;
};
const wait=async()=>{for(let n=0;n<30;n++){try{const health=await request('/health');if(health.ok&&health.codex)return health;}catch{}await new Promise(r=>setTimeout(r,1000));}throw Error(`Pocket did not become ready. Check ${log}/current`);};
const run=(command,args)=>{const result=spawnSync(command,args,{stdio:'inherit'});if(result.status!==0)throw Error(`${command} failed.`);};

async function install(){
  mkdirSync(service,{recursive:true,mode:0o700});mkdirSync(join(service,'log'),{recursive:true,mode:0o700});mkdirSync(data,{recursive:true,mode:0o700});mkdirSync(log,{recursive:true,mode:0o700});
  const command=JSON.stringify([codex,'app-server']);
  const script=`#!${prefix}/bin/sh\nexec 2>&1\nexport NODE_ENV=production\nexport PORT=18880\nexport POCKET_LOCAL=1\nexport POCKET_HOST_NAME='This phone'\nexport POCKET_DEFAULT_CWD='${home}'\nexport POCKET_DATA='${data}'\nexport POCKET_CODEX_COMMAND='${command}'\nexport POCKET_CODEX_SANDBOX=danger-full-access\nexport POCKET_CODEX_APPROVAL_POLICY=on-request\ncd '${root}'\nexec '${prefix}/bin/node' '${root}/server/index.mjs'\n`;
  const logger=`#!${prefix}/bin/sh\nmkdir -p '${log}'\nexec '${prefix}/bin/svlogd' -tt '${log}'\n`;
  writeFileSync(join(service,'run'),script,{mode:0o700});writeFileSync(join(service,'log/run'),logger,{mode:0o700});chmodSync(service,0o700);chmodSync(join(service,'log'),0o700);
  rmSync(join(service,'down'),{force:true});
  const startServices=join(prefix,'etc/profile.d/start-services.sh');if(existsSync(startServices))run(join(prefix,'bin/sh'),[startServices]);
  for(let n=0;n<30&&!existsSync(join(service,'supervise/ok'));n++)await new Promise(r=>setTimeout(r,100));
  run(join(prefix,'bin/sv'),['restart',service]);await wait();
  const configured=spawnSync(codex,['mcp','get','pocket-phone'],{stdio:'ignore'}).status===0;
  if(configured)run(codex,['mcp','remove','pocket-phone']);
  run(codex,['mcp','add','pocket-phone','--env',`POCKET_AUTOMATION_SECRET_FILE=${join(data,'automation.json')}`,'--',join(prefix,'bin/node'),join(root,'server/phone-mcp.mjs')]);
  // Screen reads stay frictionless. Actions retain Codex's normal approval
  // policy so content on screen cannot silently authorize a tap or text entry.
  appendFileSync(join(home,'.codex/config.toml'),`\n[mcp_servers.pocket-phone.tools.phone_screen]\napproval_mode = "approve"\n\n[mcp_servers.pocket-phone.tools.phone_screenshot]\napproval_mode = "approve"\n`);
  const bootDir=join(home,'.termux/boot');mkdirSync(bootDir,{recursive:true,mode:0o700});
  const boot=`#!${prefix}/bin/sh\ntermux-wake-lock 2>/dev/null || true\n. '${prefix}/etc/profile.d/start-services.sh'\n'${prefix}/bin/sv' up '${service}'\n`;
  writeFileSync(join(bootDir,'20-pocket-local'),boot,{mode:0o700});
  console.log('Pocket local runtime is ready on 127.0.0.1:18880.');
}
async function pair(){
  await wait();const {adminToken}=JSON.parse(readFileSync(join(data,'secrets.json')));const result=await request('/api/pairing',{},adminToken);
  // Some Termux:API releases hand URLs to Android through an unquoted shell
  // command, so a second query parameter can be lost at `&`. The app already
  // knows the fixed loopback address while adding its local profile.
  const uri=`pocket://pair?code=${encodeURIComponent(result.code)}`;
  const openUrl=join(prefix,'bin/termux-open-url');
  const clipboard=join(prefix,'bin/termux-clipboard-set');
  const copied=existsSync(clipboard)&&spawnSync(clipboard,{input:result.code,stdio:['pipe','ignore','ignore'],timeout:5000}).status===0;
  if(existsSync(openUrl))spawnSync(openUrl,[uri],{stdio:'ignore'});
  console.log(`Open Pocket and connect Codex on this phone. The one-time code${copied?' is copied to your clipboard':` is ${result.code}`}.`);
}

if(action==='install'){await install();await pair();}
else if(action==='restart')await install();
else if(action==='pair')await pair();
else if(action==='status'){const health=await request('/health');console.log(JSON.stringify(health));}
else throw Error('Usage: node scripts/android-local.mjs [install|restart|pair|status]');
