import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {saveRefreshedAuth} from '../maintainer/reviewer.mjs';
test('service auth refresh preserves account and concurrent login changes',()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-auth-test-')),path=join(dir,'auth.json');
 const auth=(account,access)=>JSON.stringify({auth_mode:'chatgpt',tokens:{account_id:account,access_token:access,refresh_token:'synthetic-refresh',id_token:'synthetic-id'}});
 const original=auth('test-account','old'),updated=auth('test-account','new');
 try{
  writeFileSync(path,original,{mode:0o600});saveRefreshedAuth(path,original,updated);assert.equal(readFileSync(path,'utf8'),updated);
  saveRefreshedAuth(path,original,auth('test-account','stale'));assert.equal(readFileSync(path,'utf8'),updated);
  assert.throws(()=>saveRefreshedAuth(path,updated,auth('different-account','new')),/identity/);
  assert.equal(readFileSync(path,'utf8'),updated);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
