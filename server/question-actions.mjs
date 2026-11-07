import {nativeRequestResult} from './native-request-contract.mjs';
const fail=(status,message)=>{throw Object.assign(new Error(message),{status});};
export class QuestionActions {
  constructor({pending,codex,db,resolve}){Object.assign(this,{pending,codex,db,resolve});this.sent=new WeakSet();}
  answer(id,body={}){
    const m=this.pending.get(String(id));
    if(!m)fail(409,'This request has already been answered or expired.');
    if(body.requestToken!==undefined&&m._pocketUI?.token&&body.requestToken!==m._pocketUI.token)fail(409,'This request changed or reconnected. Refresh it before answering.');
    if(this.sent.has(m))return {ok:true,submitted:true};
    let result;
    if(/requestUserInput$/.test(m.method)){
      const questions=m.params?.questions||[];
      if(!questions.length)fail(400,'This question has no answer fields.');
      const answers={};
      for(const q of questions){
        if(body.skip===true){answers[q.id]={answers:[]};continue;}
        const answer=body.answers?.[q.id];
        if(typeof answer!=='string'||!(q.isSecret?answer:answer.trim()))fail(400,`Answer ${q.header||q.id} first.`);
        if(answer.length>8000)fail(400,`Answer ${q.header||q.id} is too long.`);
        answers[q.id]={answers:[q.isSecret?answer:answer.trim()]};
      }
      result={answers};
    }else result=nativeRequestResult(m,body);
    try{this.codex.answer(m.id,result);}catch(error){throw Object.assign(error,{status:error.status||503});} // A transport error leaves this request retryable.
    this.sent.add(m);
    return {ok:true,submitted:true}; // serverRequest/resolved owns removal and attention resolution.
  }
  skipNotification(id){
    const n=this.db.prepare('SELECT n.kind,n.thread_id,a.request_id,a.resolved_at FROM notifications n LEFT JOIN notification_attention a ON a.notification_id=n.id WHERE n.id=?').get(id);
    if(!n)fail(404,'Notification not found.');
    if(n.kind!=='question'||!n.request_id)fail(400,'Only Codex questions can be skipped.');
    const m=this.pending.get(String(n.request_id));
    if(n.resolved_at!==null||!m){this.resolve('request_id=?',String(n.request_id));return {ok:true,expired:true};}
    if(!/requestUserInput$/.test(m.method)||m.params?.threadId!==n.thread_id)fail(409,'This notification no longer matches the question.');
    return this.answer(n.request_id,{skip:true,...(m._pocketUI?.token?{requestToken:m._pocketUI.token}:{})});
  }
}
