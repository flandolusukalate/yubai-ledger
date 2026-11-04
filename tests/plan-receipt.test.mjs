import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const month='2026-11',plan={month,income:300000,savings:60000,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:240000,daily:true,floor:0}]};
const receipt={accountId:'cash',occurredAt:'2026-11-02T04:00:00Z',existingIncomeIds:[]};
const receive=s=>C.reduce(s,{type:'RECEIVE_PLAN',value:{month,...receipt}});
test('plan and checked receipt save atomically and replay never credits twice',()=>{
 const action={type:'PLAN',value:plan,options:{receipt}};
 let s=C.reduce(C.initialState(),action);assert.equal(s.transactions.length,1);assert.equal(C.accountBalance(s,'cash'),300000);assert.equal(C.summary(s,month).receivedPlan,300000);
 s=C.reduce(s,action);s=receive(s);assert.equal(s.transactions.length,1);assert.equal(C.accountBalance(s,'cash'),300000);
});
test('unchecked plan does not credit funds; an existing plan can receive its allowance',()=>{
 const s=C.reduce(C.initialState(),{type:'PLAN',value:plan});assert.equal(s.transactions.length,0);assert.equal(receive(s).transactions.length,1);
});
test('partial matched receipt credits only the outstanding amount across accounts',()=>{
 let s=C.reduce(C.initialState(),{type:'PLAN',value:plan});s=C.reduce(s,{type:'POST',value:{type:'income',amount:100000,incomePlan:month,accountId:'savings',occurredAt:receipt.occurredAt}});s=receive(s);
 assert.equal(s.transactions[1].amount,200000);assert.equal(C.summary(s,month).income,300000);assert.equal(C.summary(s,month).receivedPlan,300000);
});
test('explicitly linking existing receipts does not create duplicate income',()=>{
 let s=C.initialState();for(const [id,amount] of [['part1',100000],['part2',200000]])s=C.reduce(s,{type:'POST',value:{id,type:'income',amount,accountId:'cash',occurredAt:receipt.occurredAt}});
 s=C.reduce(s,{type:'PLAN',value:plan,options:{receipt:{...receipt,existingIncomeIds:['part1','part2']}}});assert.equal(s.transactions.length,2);assert.equal(C.accountBalance(s,'cash'),300000);assert.equal(C.summary(s,month).receivedPlan,300000);
 s=receive(C.restore(C.backup(s)));assert.equal(s.transactions.length,2);
});
test('invalid receipt or invalid linked income leaves the original state untouched',()=>{
 const old=C.initialState(),before=JSON.stringify(old);
 for(const r of [{...receipt,accountId:'missing'},{...receipt,occurredAt:'2026-10-01T04:00:00Z'},{...receipt,existingIncomeIds:['missing']}])assert.throws(()=>C.reduce(old,{type:'PLAN',value:plan,options:{receipt:r}}));
 assert.equal(JSON.stringify(old),before);
});
test('voiding receipt recalculates paid status and later marking received cannot restore both',()=>{
 let s=C.reduce(C.initialState(),{type:'PLAN',value:plan,options:{receipt}});const original=s.transactions[0].id;s=C.reduce(s,{type:'VOID',value:original});s=receive(s);
 assert.equal(C.accountBalance(s,'cash'),300000);assert.throws(()=>C.reduce(s,{type:'VOID',value:original}),/超过/);
});
test('closed month and overmatching are rejected, zero allowance never creates a zero bill',()=>{
 let s=C.reduce(C.initialState(),{type:'PLAN',value:plan});s.closures[month]={};assert.throws(()=>receive(s));
 s=C.reduce(C.initialState(),{type:'POST',value:{id:'too-large',type:'income',amount:400000,accountId:'cash',occurredAt:receipt.occurredAt}});
 assert.throws(()=>C.reduce(s,{type:'PLAN',value:plan,options:{receipt:{...receipt,existingIncomeIds:['too-large']}}}));
 const zero=C.reduce(C.initialState(),{type:'PLAN',value:{...plan,income:0},options:{receipt}});assert.equal(zero.transactions.length,0);
});
