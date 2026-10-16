import {statfsSync} from 'node:fs';
export function checkBuildStorage(stage, statfs = statfsSync, ramPaths = []) {
 const available = path => { const s = statfs(path); return Number(s.bavail) * Number(s.bsize); };
 const rootAvailable = available('/'), stageAvailable = available(stage);
 // When source, outputs, Gradle cache and staging are all tmpfs, only small
 // metadata/log writes touch root. Artifact headroom belongs to staging.
 const allRam = ramPaths.length === 3 && [stage,...ramPaths].every(path => Number(statfs(path).type) === 0x01021994);
 const rootWriteHeadroom = (allRam ? 16 : 512) * 1024 ** 2;
 const rootRequired = 80 * 1024 ** 3 + rootWriteHeadroom;
 const stageRequired = 1024 ** 3;
 if (rootAvailable < rootRequired) throw Error('Build recovery reserve unavailable on root filesystem.');
 if (stageAvailable < stageRequired) throw Error('Build staging space unavailable.');
 return {rootAvailable, rootRequired, rootWriteHeadroom, allRam, stageAvailable, stageRequired};
}
