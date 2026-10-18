// Integration actors use the same owner-issued pairing flow as the app.
// An operator token remains a control-plane credential, never a human sender.
export async function pairedTestClient(base,ownerToken){
 const headers={'Content-Type':'application/json'};
 const codeResponse=await fetch(base+'/api/pairing',{method:'POST',headers:{...headers,Authorization:'Bearer '+ownerToken},body:'{}',signal:AbortSignal.timeout(5000)});
 if(!codeResponse.ok)throw Error('Test pairing-code admission failed: '+codeResponse.status);
 const {code}=await codeResponse.json();
 const pairResponse=await fetch(base+'/api/pair',{method:'POST',headers,body:JSON.stringify({code,name:'Synthetic human integration client'}),signal:AbortSignal.timeout(5000)});
 if(!pairResponse.ok)throw Error('Test device pairing failed: '+pairResponse.status);
 const {token}=await pairResponse.json();
 if(typeof token!=='string'||!token)throw Error('Test pairing returned no device token');
 return async(path,body)=>{
  const response=await fetch(base+path,{method:body?'POST':'GET',headers:{...headers,Authorization:'Bearer '+token},...(body?{body:JSON.stringify(body)}:{})});
  return {status:response.status,data:await response.json()};
 };
}
