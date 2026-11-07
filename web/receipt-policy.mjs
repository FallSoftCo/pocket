/** A successful HTTP exchange is not a delivery acknowledgement. These checks
 * apply only to the original endpoint and immutable intent, never a replacement.
 */
export const bindingMatches=(a,b)=>!!a&&!!b&&['bindingId','installationId','authorityId','principalId'].every(k=>typeof a[k]==='string'&&a[k]&&a[k]===b[k]);
export const jsonEqual=(a,b)=>{
 if(a===b)return true;
 if(!a||!b||typeof a!=='object'||typeof b!=='object'||Array.isArray(a)!==Array.isArray(b))return false;
 const keys=Object.keys(a);return keys.length===Object.keys(b).length&&keys.every(k=>Object.hasOwn(b,k)&&jsonEqual(a[k],b[k]));
};
export function deliveryAccepted(intent,receipt){
 if(!intent||!receipt||receipt.state==='unknown'||receipt.state==='failed')return false;
 if(intent.room){
  const e=receipt.event,b=intent.body,identity=intent.route.binding;
  if(!bindingMatches(identity,receipt.binding)||receipt.accepted!==true||!e||
   typeof e.id!=='string'||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(e.id)||!Number.isSafeInteger(e.seq)||e.seq<=0||e.authority!==identity.authorityId||e.author!==identity.principalId||
   e.room!==intent.room||e.kind!==b.kind||e.body!==b.body||e.audience!==b.audience||
   (e.recipient??null)!==(b.recipientId??null)||(e.source??null)!==(b.sourceEventId??null)||
   !Array.isArray(receipt.deliveries))return false;
  if(receipt.operationId!=null&&receipt.operationId!==intent.id)return false;
  return receipt.deliveries.length===0?
   b.kind==='message'||e.data?.conversationVersion===1&&e.data.authorKind==='human':
   b.kind==='request'&&receipt.deliveries.length===1&&receipt.deliveries[0].agent===b.recipientId;
 }
 if(receipt.id!==intent.id)return false;
 if(intent.scope==='coordinator')return receipt.transcript===intent.text&&
  ['queued','thinking','speaking','completed'].includes(receipt.state)&&!receipt.destination?.roomId;
 return receipt.thread_id===intent.scope&&['queued','held','sending','accepted'].includes(receipt.state);
}
