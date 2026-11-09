import {readFileSync,writeFileSync,renameSync,openSync,fsyncSync,closeSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {Codex} from '../server/codex.mjs';
import {runAccountReset,accountResetDue} from '../server/account-reset.mjs';

// The owning systemd job holds flock across this entire process, including RPC.
const path=resolve(process.env.POCKET_RESET_STATE||'data/account-reset.json');
const state=JSON.parse(readFileSync(path,'utf8'));
const save=value=>{
 const tmp=path+'.tmp';writeFileSync(tmp,JSON.stringify(value,null,2)+'\n',{mode:0o600});
 const fd=openSync(tmp,'r');try{fsyncSync(fd);}finally{closeSync(fd);}renameSync(tmp,path);
 const dir=openSync(dirname(path),'r');try{fsyncSync(dir);}finally{closeSync(dir);}
};
if(accountResetDue(state)){
 if(process.env.POCKET_CODEX_COMMAND)throw Error('Account reset requires the existing native socket.');
 const codex=new Codex();try{await codex.connect();await runAccountReset({state,call:(...args)=>codex.call(...args),save});}
 catch(error){if(error?.code==='RESET_STATE_WRITE_FAILED')throw error;state.lastCheck=Date.now();state.nextAt=Date.now()+120000;state.reason='Runtime unavailable; authorization and benefit preserved.';save(state);}
 finally{codex.closeTransport();}
}
console.log(JSON.stringify({status:state.status,weeklyUsedPercent:state.weeklyUsedPercent??null,nextAt:state.nextAt??null,reason:state.reason??null}));
