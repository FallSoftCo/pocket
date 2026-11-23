import {ownerRequest} from './local-client.mjs';
const result=await ownerRequest('/api/app/update/announce',{method:'POST',body:{}});
console.log(JSON.stringify(result));
