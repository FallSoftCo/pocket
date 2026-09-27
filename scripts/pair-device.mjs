import {spawnSync} from 'node:child_process';
import {ownerRequest} from './local-client.mjs';
try{
  if(!process.env.POCKET_PUBLIC_URL)throw Error('Set POCKET_PUBLIC_URL to your workstation HTTPS address before pairing.');
  const url=new URL(process.env.POCKET_PUBLIC_URL);
  if(url.protocol!=='https:'||url.username||url.password||url.pathname!=='/'||url.search||url.hash)throw Error('POCKET_PUBLIC_URL must be an HTTPS origin without credentials or a path.');
  const server=url.origin;
  const {code,expires}=await ownerRequest('/api/pairing',{method:'POST',body:{}});
  if(process.argv[2]){
    const quote=s=>"'"+s.replaceAll("'", "'\\''")+"'";
    const result=spawnSync('adb',['-s',process.argv[2],'shell',`am start -n co.fallsoft.pocket/.MainActivity --es server ${quote(server)} --es code ${quote(code)}`],{encoding:'utf8'});
    if(result.status!==0)throw Error('ADB could not open the pairing form. Check your device connection.');
    console.log('One-time pairing form opened on phone.');
  }else console.log(JSON.stringify({server,code,expires:new Date(expires).toISOString()},null,2));
}catch(e){console.error(e.message);process.exitCode=1;}
