import {ownerRequest} from './local-client.mjs';
try{
  const [action='list',id]=process.argv.slice(2);
  if(action==='list')console.log(JSON.stringify((await ownerRequest('/api/devices')).devices,null,2));
  else if(action==='revoke'&&/^[a-zA-Z0-9-]{5,100}$/.test(id||'')){
    await ownerRequest(`/api/devices/${id}`,{method:'DELETE'});console.log('Device revoked. Active connections closed and push registration removed.');
  }else throw Error('Usage: node scripts/devices.mjs [list | revoke DEVICE_ID]');
}catch(e){console.error(e.message);process.exitCode=1;}
