// Apply only an explicit permission change. Ordinary reads/recovery must inherit.
export async function changeThreadPermissions(codex,threadId,{full=true,coordinator=false}={}){
 const permissions=full?':danger-full-access':coordinator?':read-only':':workspace';
 const approvalPolicy=full||coordinator?'never':'on-request';
 await codex.call('thread/resume',{threadId,excludeTurns:true});
 let rejected;
 try{await codex.call('thread/settings/update',{threadId,permissions,approvalPolicy});}
 catch(error){if(!/direct app-server input is not allowed for multi-agent v2 sub-agents/.test(error.message))throw error;rejected=error;}
 const actual=await codex.call('thread/resume',{threadId,excludeTurns:true});
 const expectedSandbox=full?'dangerFullAccess':coordinator?'readOnly':'workspaceWrite';
 if(actual.activePermissionProfile?.id!==permissions||actual.approvalPolicy!==approvalPolicy||actual.sandbox?.type!==expectedSandbox){
  if(rejected)throw rejected;
  throw Object.assign(Error('Codex did not apply the requested permissions. Existing work has not been restarted.'),{status:409});
 }
 return {threadId,full,verified:true,permissions,approvalPolicy,appliesTo:'subsequent-turns'};
}
