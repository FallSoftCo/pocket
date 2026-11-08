import {environmentOf} from './environments.mjs';
const prefix='nextcomp.web.v1.';
/** Delete source-owned local state only after authenticated retirement evidence.
 * Text is never inspected for identifiers and no original command is retried. */
export function retiredValue(value, ids) {
 if(!value||typeof value!=='object')return false;
 if(ids.has(value.environment)||ids.has(value.environmentId)||ids.has(environmentOf(value.scope)))return true;
 return ['intent','route','transport'].some(key=>retiredValue(value[key],ids));
}
export function purgeRetiredStorage(storage, retiredIds) {
 const ids=new Set(retiredIds.filter(id=>typeof id==='string'&&/^[a-z][a-z0-9-]{1,30}$/.test(id)&&id!=='current'));
 const removed=[];
 for(const key of Object.keys(storage)){
  if(!key.startsWith(prefix))continue;
  const name=key.slice(prefix.length);
  let value;try{value=JSON.parse(storage.getItem(key));}catch{continue;}
  const scopedKey=[...ids].some(id=>name.includes('.env_'+id+'__')||name.includes('"'+id+'"'));
  if(scopedKey||retiredValue(value,ids)||name==='scope'&&ids.has(environmentOf(value))){storage.removeItem(key);removed.push(name);}
 }
 return removed;
}
