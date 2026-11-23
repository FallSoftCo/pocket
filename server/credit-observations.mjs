import {createHmac} from 'node:crypto';

export function creditBalance(value){
 const text=typeof value==='number'&&Number.isFinite(value)?String(value):value;
 if(typeof text!=='string'||!/^\d{1,24}(?:\.\d{1,12})?$/.test(text))return null;
 return text.replace(/^0+(?=\d)/,'').replace(/(\.\d*?)0+$/,'$1').replace(/\.$/,'');
}
const units=value=>{const [whole,fraction='']=value.split('.');return BigInt(whole)*10n**12n+BigInt(fraction.padEnd(12,'0'));};
export function balanceDecrease(before,after){
 const difference=units(before)-units(after);if(difference<0n)return null;
 return creditBalance(`${difference/10n**12n}.${String(difference%10n**12n).padStart(12,'0')}`);
}

/** Private observations, never an invoice or attribution to a particular task. */
export class CreditObservations {
 constructor(db,key){this.db=db;this.key=key;db.exec(`CREATE TABLE IF NOT EXISTS account_credit_observations(id INTEGER PRIMARY KEY,account_key TEXT NOT NULL,at INTEGER NOT NULL,balance TEXT,epoch INTEGER NOT NULL,reason TEXT NOT NULL);CREATE INDEX IF NOT EXISTS account_credit_observations_lookup ON account_credit_observations(account_key,id);`);}
 accountKey(id){return createHmac('sha256',this.key).update(id).digest('hex');}
 boundary(id,at){if(id)this.observe(id,null,at);}
 observe(id,credits,at){
  if(!id)return {observation:null,comparisonState:'account-unverified'};
  const key=this.accountKey(id),balance=credits?.unlimited===true?null:creditBalance(credits?.balance);
  const last=this.db.prepare('SELECT * FROM account_credit_observations WHERE account_key=? ORDER BY id DESC LIMIT 1').get(key);
  if(last&&at<=last.at)return {observation:null,comparisonState:'out-of-order'};
  let epoch=last?.epoch||0,reason='continuous';
  if(balance===null){epoch++;reason='unavailable';}
  else if(!last||last.balance===null){epoch++;reason='baseline';}
  else if(balanceDecrease(last.balance,balance)===null){epoch++;reason='balance-increased';}
  if(!last||at>last.at)this.db.prepare('INSERT INTO account_credit_observations(account_key,at,balance,epoch,reason) VALUES(?,?,?,?,?)').run(key,at,balance,epoch,reason);
  if(balance===null)return {observation:null,comparisonState:reason};
  const first=this.db.prepare('SELECT at,balance,reason FROM account_credit_observations WHERE account_key=? AND epoch=? AND balance IS NOT NULL ORDER BY id LIMIT 1').get(key,epoch);
  return {comparisonState:first?.reason||reason,observation:first&&at>first.at?{fromAt:first.at,toAt:at,balanceDecrease:balanceDecrease(first.balance,balance)}:null};
 }
}
