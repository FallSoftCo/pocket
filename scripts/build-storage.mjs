import {statfsSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
export function checkBuildStorage(stage, statfs = statfsSync, buildPaths = []) {
 // A fresh checkout has no outputs yet. Inspect their existing ancestor's
 // filesystem; propagate access errors and retain the same reserve/RAM guards.
 const filesystem=path=>{let probe=resolve(path);for(;;){try{return statfs(probe);}catch(error){if(error.code!=='ENOENT'&&error.code!=='ENOTDIR')throw error;const parent=dirname(probe);if(parent===probe)throw error;probe=parent;}}};
 const available = path => { const s = filesystem(path); return Number(s.bavail) * Number(s.bsize); };
 const rootAvailable = available('/'), stageAvailable = available(stage);
 // Source, dependencies, outputs, caches and release staging must be disk-backed.
 // Low disk space must never silently turn a build into host RAM consumption.
 for(const path of [stage,...buildPaths]){
  const type=Number(filesystem(path).type);
  if(type===0x01021994||type===0x858458f6)throw Error('RAM-backed build path is forbidden: '+path);
 }
 const rootWriteHeadroom = 512 * 1024 ** 2;
 const rootRequired = 8 * 1024 ** 3 + rootWriteHeadroom;
 const stageRequired = 1024 ** 3;
 if (rootAvailable < rootRequired) throw Error('Build recovery reserve unavailable on root filesystem.');
 if (stageAvailable < stageRequired) throw Error('Build staging space unavailable.');
 return {rootAvailable, rootRequired, rootWriteHeadroom, stageAvailable, stageRequired};
}
