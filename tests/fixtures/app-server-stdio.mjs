import readline from 'node:readline';

const input=readline.createInterface({input:process.stdin});
input.on('line',line=>{
  const message=JSON.parse(line);
  if(message.method==='initialized'){
    process.stdout.write(JSON.stringify({method:'server/ready',params:{transport:'stdio'}})+'\n');
    return;
  }
  if(message.id!==undefined){
    process.stdout.write(JSON.stringify({id:message.id,result:{method:message.method,params:message.params}})+'\n');
  }
});
