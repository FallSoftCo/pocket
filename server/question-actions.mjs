const fail=(status,message)=>{throw Object.assign(new Error(message),{status});};
export class QuestionActions {
  constructor({pending,codex,db,resolve}){Object.assign(this,{pending,codex,db,resolve});this.sent=new WeakSet();}
  answer(id,body={}){
    const m=this.pending.get(String(id));
    if(!m)fail(409,'This request has already been answered or expired.');
    if(this.sent.has(m))return {ok:true,submitted:true};
    let result;
    if(/requestUserInput$/.test(m.method)){
      const questions=m.params?.questions||[];
      if(!questions.length)fail(400,'This question has no answer fields.');
      const answers={};
      for(const q of questions){
        if(body.skip===true){answers[q.id]={answers:[]};continue;}
        const answer=body.answers?.[q.id];
        if(typeof answer!=='string'||!answer.trim())fail(400,`Answer ${q.header||q.id} first.`);
        answers[q.id]={answers:[answer.trim().slice(0,8000)]};
      }
      result={answers};
    }else if(/item\/(commandExecution|fileChange)\/requestApproval$/.test(m.method)){
      if(body.skip)fail(400,'Approval requests cannot be skipped.');
      if(!['accept','decline','cancel'].includes(body.decision))fail(400,'Invalid approval decision');
      if(m.params.availableDecisions&&!m.params.availableDecisions.includes(body.decision))fail(400,'This decision is not offered by Codex.');
      result={decision:body.decision};
    }else fail(400,'Please answer this request in the terminal; this request type is not supported yet.');
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
    return this.answer(n.request_id,{skip:true});
  }
}
