// Retrieval evidence only: lexical scores select bounded reads, never an action
// target. The coordinator must evaluate the public conversation evidence.
const validId = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{5,100}$/.test(value);
const finite = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
const text = (value, max = 500) => typeof value === 'string' ? value
  .replace(/\bBearer\s+[^\s]+/gi, 'Bearer [redacted]')
  .replace(/\bsk-[a-zA-Z0-9_-]{12,}/g, '[redacted]')
  .replace(/\b(password|token|secret|api[_ -]?key)\s*[=:]\s*[^\s,;]+/gi, '$1=[redacted]')
  .replace(/(https?:\/\/)[^\s/@]+:[^\s/@]+@/gi, '$1[redacted]@')
  .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').slice(0, max) : '';
const status = value => text(typeof value === 'string' ? value : value?.type, 60) || 'unknown';
const words = value => [...new Set(text(value, 4000).toLowerCase().normalize('NFKD')
  .replace(/\p{M}/gu, '').match(/[\p{L}\p{N}]{3,}/gu) || [])]
  .filter(word => !new Set(['the','and','this','that','with','from','have','session','conversation','please','continue','work','task']).has(word));
const recency = session => {
  const value = session.activityAt ?? session.updatedAt ?? 0;
  return value < 1e11 ? value * 1000 : value;
};
function metadata(raw) {
  const result = {id: raw.id, name: text(raw.name, 120), cwd: text(raw.cwd, 120),
    preview: text(raw.preview, 220), previewRole: text(raw.previewRole, 30),
    status: status(raw.status), activityAt: finite(raw.activityAt), updatedAt: finite(raw.updatedAt),
    archived: raw.archived === true, discoveryPending: raw.discoveryPending === true};
  const parentId = raw.parentThreadId ?? raw.parentId ?? raw.source?.subAgent?.threadSpawn?.parentThreadId;
  if (validId(parentId)) result.parentThreadId = parentId;
  if (raw.managedChild === true || validId(parentId) || raw.source?.type === 'subAgent') result.managedChild = true;
  return result;
}
const activityNames = {commandExecution:'Run command', fileChange:'File changes', mcpToolCall:'Tool activity',
  dynamicToolCall:'Tool activity', webSearch:'Web search', collabAgentToolCall:'Agent activity',
  subAgentActivity:'Agent activity', imageGeneration:'Image generation', imageView:'View image', plan:'Plan',
  enteredReviewMode:'Code review', exitedReviewMode:'Code review'};
function evidence(raw, listed, id) {
  if (!raw?.thread || raw.thread.id !== id) throw Object.assign(Error('Mismatched thread'), {code:'mismatched_thread'});
  const summary = {...listed, ...metadata({...listed, ...raw.thread, id}), availability:'available'};
  const rows = Array.isArray(raw.timeline?.rows) ? raw.timeline.rows.slice(-200) : [];
  const publicRows = rows.filter(row => row && ['userMessage','agentMessage'].includes(row.type));
  summary.messages = publicRows.slice(-8).map(row => ({role:row.type === 'userMessage' ? 'user' : 'assistant',
    text:text(row.text, 1000), ...(validId(row.turnId) ? {turnId:row.turnId} : {}),
    ...(typeof row.phase === 'string' ? {phase:text(row.phase, 30)} : {})})).filter(row => row.text);
  // A busy commentary stream must not erase the latest initiating user request.
  const latestUser = publicRows.findLast(row => row.type === 'userMessage');
  const latestAssistant = publicRows.findLast(row => row.type === 'agentMessage');
  summary.latestUser = text(latestUser?.text, 1200);
  summary.latestAssistant = text(latestAssistant?.text, 1200);
  summary.activities = rows.filter(row => activityNames[row?.type]).slice(-4).map(row => ({
    type:row.type, title:activityNames[row.type], status:status(row.status),
    // No raw shell arguments, tool results, logs or reasoning in routing context.
    ...(['webSearch','fileChange','plan'].includes(row.type) ? {text:text(row.text, 350)} : {})}));
  summary.notes = (Array.isArray(raw.notes) ? raw.notes : []).slice(0, 3)
    .map(note => ({text:text(note?.text, 500), question:text(note?.question, 200)})).filter(note => note.text);
  summary.history = {scope:Array.isArray(raw.timeline?.rows) ? 'recent' : 'unavailable', hasEarlier:raw.timeline?.hasEarlier === true,
    rowsAvailable:rows.length, truncated:raw.timeline?.hasEarlier === true || (raw.timeline?.rows?.length ?? 0) > 200};
  summary.pendingCount = Array.isArray(raw.pending) ? raw.pending.length : raw.pending ? 1 : 0;
  summary.outgoing = (Array.isArray(raw.outgoing) ? raw.outgoing : []).slice(-4)
    .map(item => ({state:text(item?.state, 40), mode:text(item?.mode, 20)}));
  return summary;
}
function errorCode(error) {
  if (error?.code === 'timeout') return 'timeout';
  if (error?.code === 'mismatched_thread') return 'mismatched_thread';
  if (error?.status === 404) return 'not_found';
  if (error?.status === 410) return 'gone';
  return 'unavailable';
}
async function bounded(call, ms) {
  let timer;
  try {
    return await Promise.race([Promise.resolve().then(call), new Promise((_, reject) => {
      timer = setTimeout(() => reject(Object.assign(Error('Read timed out'), {code:'timeout'})), Math.max(1, ms));
    })]);
  } finally { clearTimeout(timer); }
}

export class CoordinatorDiscovery {
  constructor({api, owns = () => false, clock = Date.now, readTimeoutMs = 3000, totalTimeoutMs = 6000}) {
    Object.assign(this, {api, owns, clock, readTimeoutMs, totalTimeoutMs});
  }
  async discover({query = '', threadIds = [], limit = 6, cursor: startCursor = null} = {}) {
    const checkedAt = this.clock(), deadline = Date.now() + this.totalTimeoutMs;
    const catalogById = new Map(), errors = [];
    let nextCursor = typeof startCursor === 'string' && startCursor ? startCursor.slice(0, 2000) : null, refreshPending = false, complete = false;
    const seenCursors = new Set();
    for (let page = 0; page < 6 && catalogById.size < 600 && Date.now() < deadline; page++) {
      try {
        const path = `/api/threads?view=coordinator${nextCursor ? `&cursor=${encodeURIComponent(nextCursor)}` : ''}`;
        const result = await bounded(() => this.api(path), Math.min(this.readTimeoutMs, deadline - Date.now()));
        if (!Array.isArray(result?.threads)) throw Error('Invalid list');
        refreshPending ||= result.refreshPending === true;
        const before = catalogById.size;
        for (const raw of result.threads) {
          if (catalogById.size >= 600) break;
          if (validId(raw?.id) && !this.owns(raw.id)) catalogById.set(raw.id, metadata(raw));
        }
        nextCursor = typeof result.nextCursor === 'string' && result.nextCursor ? result.nextCursor.slice(0, 2000) : null;
        const overflow = result.threads.some(raw => validId(raw?.id) && !this.owns(raw.id) && !catalogById.has(raw.id));
        if (!nextCursor) { complete = !overflow && !refreshPending; break; }
        if (seenCursors.has(nextCursor) || catalogById.size === before) {
          errors.push({operation:'list', code:'repeated_page'}); break;
        }
        seenCursors.add(nextCursor);
      } catch (error) { errors.push({operation:'list', code:errorCode(error)}); break; }
    }
    const allCatalog = [...catalogById.values()];
    const maxReads = Math.max(1, Math.min(12, Number.isFinite(limit) ? Math.floor(limit) : 6));
    const explicit = [...new Set(Array.isArray(threadIds) ? threadIds.filter(validId) : [])].filter(id => !this.owns(id));
    const terms = words(query);
    const ranked = allCatalog.map(session => {
      const titleWords = words(session.name), previewWords = words(session.preview), cwdWords = words(session.cwd);
      const score = terms.reduce((sum, term) => sum + (previewWords.includes(term) ? 3 : 0)
        + (titleWords.includes(term) ? 2 : 0) + (cwdWords.includes(term) ? 1 : 0), 0);
      return {session, score};
    }).sort((a,b) => b.score - a.score || recency(b.session) - recency(a.session) || a.session.id.localeCompare(b.session.id));
    const chosen = new Set(explicit.slice(0, maxReads));
    // Reserve diversity: strong lexical evidence, active work and recent work.
    const take = id => { if (chosen.size < maxReads && id) chosen.add(id); };
    for (const entry of ranked.filter(entry => entry.score > 0).slice(0, Math.max(1, Math.ceil(maxReads / 2)))) take(entry.session.id);
    const recent = [...allCatalog].sort((a,b) => recency(b) - recency(a) || a.id.localeCompare(b.id));
    take(recent.find(session => ['active','pending','inProgress'].includes(session.status) && !chosen.has(session.id))?.id);
    take(recent.find(session => !chosen.has(session.id))?.id);
    for (const entry of ranked) take(entry.session.id);
    if (explicit.length > maxReads) errors.push({operation:'read', code:'reference_limit', omitted:explicit.length - maxReads});
    const ids = [...chosen], sessions = new Array(ids.length);
    let cursor = 0;
    const worker = async () => {
      while (cursor < ids.length) {
        const index = cursor++, id = ids[index];
        const listed = catalogById.get(id) || {id, name:'', status:'unknown', archived:false};
        try {
          // Do not launch another network read for a nearly exhausted budget.
          if (deadline - Date.now() <= 5) throw Object.assign(Error('Deadline'), {code:'timeout'});
          const raw = await bounded(() => this.api(`/api/threads/${encodeURIComponent(id)}?view=timeline`),
            Math.min(this.readTimeoutMs, deadline - Date.now()));
          sessions[index] = evidence(raw, listed, id);
        } catch (error) {
          const code = errorCode(error);
          sessions[index] = {...listed, availability:'unavailable', readError:code, history:{scope:'unavailable'}};
          errors.push({operation:'read', threadId:id, code});
        }
      }
    };
    await Promise.all(Array.from({length:Math.min(3, ids.length)}, worker));
    const catalogIds = new Set(explicit.filter(id => catalogById.has(id)).slice(0, 120));
    for (const id of chosen) if (catalogById.has(id) && catalogIds.size < 120) catalogIds.add(id);
    for (const entry of ranked) if (catalogIds.size < 120) catalogIds.add(entry.session.id);
    const catalog = [...catalogIds].map(id => catalogById.get(id));
    const truncated = allCatalog.length > catalog.length;
    return {sessions, catalog, catalogCount:catalog.length, listedCount:allCatalog.length, truncated,
      checkedAt, scope:{complete:complete && !truncated && !startCursor, refreshPending, nextCursor}, errors};
  }
}
