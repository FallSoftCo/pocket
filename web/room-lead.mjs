/** Verified communication policy; never infer execution from an assignment. */
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
export function roomLead(room){
 const p=room?.leadPolicy;
 if(p===undefined)return null;
 if(p?.version!==1||!uuid(p.leadAgentId)||!Number.isSafeInteger(p.revision)||p.revision<1||!Number.isSafeInteger(p.inputAfterSeq)||p.inputAfterSeq<0)throw Error('Project lead metadata is unavailable. Saved input is retained.');
 return {agent:p.leadAgentId,name:typeof p.leadName==='string'&&p.leadName.trim()?p.leadName:'Project lead',revision:p.revision,after:p.inputAfterSeq};
}
export function leadConversation(events,policy){
 if(!policy)return [...events];
 return [...events].filter(e=>e.seq>policy.after&&e.data?.channel==='human'&&e.data?.leadRevision===policy.revision);
}
export function assertLeadTarget(room,target){
 const lead=roomLead(room);
 if(lead&&target.recipientId&&target.recipientId!==lead.agent)throw Error('This saved recipient belongs to the earlier room. Inspect its original delivery, then choose the lead for new input.');
 return lead;
}
