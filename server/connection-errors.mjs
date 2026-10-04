export function codexConnectionError(code='CODEX_DISCONNECTED',cause){
  const messages={
    CODEX_DISCONNECTED:'Your phone can reach the workstation, but NextComp lost its connection to Codex there. NextComp reconnects automatically. Reload the conversation; check it before resending a reply.',
    CODEX_UNAVAILABLE:'NextComp is running on your workstation, but cannot reach Codex there. Check that Codex is open on that workstation. NextComp will keep trying to reconnect.',
    CODEX_TIMEOUT:'Your workstation is reachable, but Codex did not answer in time. Reload the conversation. Check whether a reply arrived before sending it again.',
    CODEX_HISTORY_TOO_LARGE:'NextComp could not load this conversation because a Codex response exceeded its history size limit. Your phone reached the workstation. Open this task in the workstation’s terminal; other tasks may still load.'
  };
  return Object.assign(new Error(messages[code],{cause}),{code,status:503});
}
export const ambiguousDelivery=e=>['CODEX_DISCONNECTED','CODEX_TIMEOUT','CODEX_HISTORY_TOO_LARGE'].includes(e.code)||/timed out|disconnected/.test(e.message);
