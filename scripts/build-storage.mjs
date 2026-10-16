import {statfsSync} from 'node:fs';
export function checkBuildStorage(stage, statfs = statfsSync) {
 const available = path => { const s = statfs(path); return Number(s.bavail) * Number(s.bsize); };
 const rootAvailable = available('/'), stageAvailable = available(stage);
 const rootRequired = 80 * 1024 ** 3 + 512 * 1024 ** 2;
 const stageRequired = 1024 ** 3;
 if (rootAvailable < rootRequired) throw Error('Build recovery reserve unavailable on root filesystem.');
 if (stageAvailable < stageRequired) throw Error('Build staging space unavailable.');
 return {rootAvailable, rootRequired, stageAvailable, stageRequired};
}
