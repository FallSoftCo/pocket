import {milliseconds} from './session-catalog.mjs';

/** Native work recency wins over metadata edits (rename/index/migration).
 * Only runtimes without work timestamps use updatedAt as a fallback. */
export function archiveActivityAt(thread){
  const work=Math.max(milliseconds(thread?.recencyAt),milliseconds(thread?.activityAt));
  return Math.max(work||milliseconds(thread?.updatedAt),milliseconds(thread?.createdAt));
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
