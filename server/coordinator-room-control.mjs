export const GATEWAY_ROOM_CONTROL='nextcomp-project-rooms/v1';
export const CANONICAL_ROOM_CONTROL='hollywood-project-rooms/v1';
export const ROOM_CREATION_AUTHORITY_GUIDANCE='For a NEW project room, use rooms.creation and its explicitly advertised current authority/creator/sourceRoomIds. A lexical match to an old room title is not creation authority; selected stock Codex focus is irrelevant. Read-only historical pairings remain useful but cannot choose a writer. Do not turn a request for a NEW room into work/delegation to an old project agent. Discovery must show supported gateway AND canonical human policy; missing advertisements are read-only, not consent to try a POST. Keep sourceRoomId from that creator capability. For an original metadata creation, roomReceipt accepts its coordinator-room commandId and ORIGINAL environmentId, including historical read-only bindings. Typed no_admission is not unknown; inaccessible_unknown is not absence. Never ask to retry or for duplicate-risk consent before admission is established. Positive original gateway rejection can be reconciled by the maintained owner-reviewed local repair, without changing original conversation/intent rows. Unsupported-source errors include current creation targets so you can choose the correct source; never synthesize grants, borrow tokens or promote the human.';
export function roomControl(identity,catalog){
 const gateway=identity?.roomControl,canonical=catalog?.roomControl;
 let reason=null;
 if(gateway?.protocol!==GATEWAY_ROOM_CONTROL||gateway.createRoom!==true)reason='GATEWAY_CREATE_UNADVERTISED';
 else if(canonical?.protocol!==CANONICAL_ROOM_CONTROL)reason='CANONICAL_CREATE_UNADVERTISED';
 else if(canonical.authorityId!==identity.authorityId||canonical.principalId!==identity.principalId)reason='CONTROL_IDENTITY_MISMATCH';
 else if(canonical.createRoom?.allowed!==true)reason=canonical.createRoom?.reason||'HUMAN_CREATION_NOT_PERMITTED';
 return {createRoom:reason===null,reason,policy:canonical?.createRoom?.policy||null,sourceRoomIds:Array.isArray(canonical?.sourceRoomIds)?canonical.sourceRoomIds:[]};
}
export function creationSources(sources){
 const allowed=sources.filter(s=>s.available&&s.control?.createRoom);
 const current=allowed.find(s=>s.environmentId==='current');
 const authorities=[...new Set(allowed.map(s=>s.authorityId))];
 const authorityId=current?.authorityId||(authorities.length===1?authorities[0]:null);
 return {available:!!authorityId,authorityId,reason:authorityId?null:authorities.length?'AMBIGUOUS_CREATION_AUTHORITY':'NO_SUPPORTED_CREATOR',
  targets:allowed.filter(s=>s.authorityId===authorityId).map(s=>({environmentId:s.environmentId,authorityId:s.authorityId,name:s.name,policy:s.control.policy,sourceRoomIds:s.control.sourceRoomIds}))};
}
export function selectRoomCreator(catalog,args){
 const creation=catalog.creation;
 const fail=message=>Object.assign(Error(message),{status:409,code:'ROOM_CREATION_SOURCE_UNSUPPORTED',admission:'no_admission',creation});
 if(!creation?.available)throw fail('No unambiguous supported project-room creator is currently advertised. Historical room readability is not creation authority.');
 const target=args.environmentId?creation.targets.find(t=>t.environmentId===args.environmentId):creation.targets[0];
 if(!target)throw fail('That source is not a supported creator in the current creation authority. Use the advertised creation targets, not a similarly named historical room.');
 const source=catalog.rooms.find(r=>r.id===args.sourceRoomId&&r.authorityId===creation.authorityId&&r.routes.some(t=>t.environmentId===target.environmentId));
 if(!source||!target.sourceRoomIds.includes(args.sourceRoomId))throw fail('Choose a canonical requestable local source room and agent from this advertised current creator. An old matching project title cannot choose the creator.');
 return target.environmentId;
}
