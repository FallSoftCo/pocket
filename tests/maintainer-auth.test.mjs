import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {saveRefreshedAuth,reviewerFailure} from '../maintainer/reviewer.mjs';
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

test('reviewer failures identify permanent auth errors without disclosing subprocess content',()=>{
 for(const code of ['refresh_token_reused','refresh_token_expired','refresh_token_invalidated','token_revoked']){
  const message=reviewerFailure(JSON.stringify({type:'error',message:`Private account, token synthetic-secret: ${code}`}), 'private stderr');
  assert.match(message,new RegExp(code));assert.match(message,/sign in again/);
  assert.doesNotMatch(message,/synthetic-secret|Private account|private stderr/);
 }
 assert.equal(reviewerFailure('private arbitrary failure','secret'), 'runtime error');
 assert.equal(reviewerFailure('refresh_token_reused','',{timedOut:true}), 'timeout');
 assert.equal(reviewerFailure('refresh_token_reused','',{toolAttempt:true}), 'tool attempt');
 assert.equal(reviewerFailure('refresh_token_reused','',{overflow:true}), 'output limit');
 assert.equal(reviewerFailure('not_refresh_token_reused_suffix',''), 'runtime error');
});
