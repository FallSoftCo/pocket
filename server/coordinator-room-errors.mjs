/** A reply-identity mismatch can occur AFTER a successful mutation; its409 is
 * not a no-admission certificate. Recognize only typed request/admission
 * refusal or original authentication refusal, never every generic4xx. */
export function definitiveRoomRefusal(error){
 if(error.code==='REMOTE_DELIVERY_UNKNOWN')return false;
 return error.admission==='no_admission'||
  [400,401,403,404,409,422].includes(error.status)&&['HOLLYWOOD_REQUEST_REJECTED','HUMAN_DEVICE_REQUIRED'].includes(error.code)||
  [401,403].includes(error.status)&&error.code!=='HOLLYWOOD_BINDING_MISMATCH';
}
