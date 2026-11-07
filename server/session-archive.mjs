import {milliseconds} from './session-catalog.mjs';

/** Never use catalog fetch time as work recency. updatedAt is a conservative
 * fallback for older runtimes that do not expose recencyAt. */
export function archiveActivityAt(thread){
  return Math.max(...['recencyAt','activityAt','updatedAt','createdAt'].map(key=>milliseconds(thread?.[key])));
}
export function inactiveArchiveReason(thread,cutoff){
  if(!Number.isSafeInteger(cutoff)||cutoff<=0)return 'Invalid archive cutoff';
  if(!thread?.id)return 'Session identity unavailable';
  if(!['idle','notLoaded'].includes(thread.status?.type))return 'Session is working or its state is unavailable';
  if(thread.needsInput||thread.status?.activeFlags?.length)return 'Session needs attention';
  const at=archiveActivityAt(thread);
  if(!at)return 'Activity time unavailable';
  if(at>=cutoff)return 'Session has recent activity';
  return null;
}
