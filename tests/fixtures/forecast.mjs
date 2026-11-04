import * as C from '../../app/src/main/assets/web/core.mjs';
export const month='2026-11';
export const at=day=>`2026-11-${String(day).padStart(2,'0')}T04:00:00Z`;
export const change=(s,type,value)=>C.reduce(s,{type,value});
export function plan(m=month){return {month:m,income:300000,savings:60000,emergency:15000,startDay:1,budgets:[['food',105000,true],['transport',15000,true],['fun',30000,true],['goods',15000,true],['trip',30000,false],['fixed',30000,false]].map(([id,allocated,daily])=>({id,name:C.CATEGORIES[id],allocated,daily,floor:0}))};}
export function base(){
 let s=change(C.initialState(),'PLAN',plan());
 s=change(s,'SETTINGS',{sources:['com.tencent.mm','com.eg.android.AlipayGphone'],pageCapture:true,pageOcr:true,alipayPageCapture:true});
 return change(s,'POST',{id:'allowance',type:'income',amount:300000,accountId:'cash',incomePlan:month,occurredAt:at(1)});
}
export function expense(amount,day=3,budgetId='food',id=C.uid()){
 return {id,type:'expense',amount,accountId:'cash',merchant:'测试消费',occurredAt:at(day),splits:[{amount,category:'food',budgetId}]};
}
export function payment(amount,day=3,origin='alipay',patch={}){
 const id=C.uid();
 return {id,fingerprint:id,source:'accessibility',captureMethod:'accessibility-window-ocr',origin,evidence:origin==='alipay'?'alipay-paid-page-v1':'wechat-paid-merchant-result-v1',type:'expense',amount,merchant:'测试商户',direction:'out',fundsStatus:'paid_out',successful:true,reliable:false,status:'pending',occurredAt:at(day),collectedAt:at(day),reference:`${origin}:${id}`,timeBasis:'page-local-time-book-zone',...patch};
}
export const summary=(s,day=3)=>C.summary(s,month,`2026-11-${String(day).padStart(2,'0')}`);
