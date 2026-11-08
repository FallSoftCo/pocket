/** Explicit supported-source advertisements for mutation fixtures. Missing
 * advertisements intentionally remain read-only in legacy/negative fixtures. */
export const qaHuman='33333333-3333-4333-8333-333333333333';
export const gatewayControl={protocol:'nextcomp-project-rooms/v1',createRoom:true};
export const canonicalControl=(authorityId,sourceRoomIds)=>({protocol:'hollywood-project-rooms/v1',authorityId,principalId:qaHuman,sourceRoomIds,createRoom:{allowed:true,policy:'source-room-request-owner'}});
