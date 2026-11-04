import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {base,change,expense,summary,at} from './fixtures/forecast.mjs';

test('user categories name spending without creating another budget or changing money',()=>{
 let s=change(base(),'CATEGORY',{id:'cat-study',name:'学习资料'});
 assert.equal(C.categories(s)['cat-study'],'学习资料');assert.equal(summary(s).D,165000);
 s=change(s,'POST',{...expense(2000,3,'goods','book'),splits:[{amount:2000,category:'cat-study',budgetId:'goods'}]});
 assert.equal(C.budget(s,'2026-11','goods').remaining,13000);assert.equal(summary(s).expenses,2000);
 assert.equal(summary(s).categorySpending.find(x=>x.id==='cat-study').net,2000);
 s=change(s,'CATEGORY',{id:'cat-study',name:'书籍与课程'});
 assert.equal(s.transactions.find(x=>x.id==='book').splits[0].category,'cat-study');assert.equal(summary(s).expenses,2000);
 s=change(s,'CATEGORY',{id:'cat-study',name:'书籍与课程',archived:true});
 assert.equal(C.categories(s,{activeOnly:true})['cat-study'],undefined);
 assert.equal(C.categories(s)['cat-study'],'书籍与课程');
 s=change(s,'POST',{type:'refund',amount:500,accountId:'cash',refundOf:'book',occurredAt:at(3)});
 assert.equal(summary(s).categorySpending.find(x=>x.id==='cat-study').net,1500);
 const restored=C.restore(C.backup(s));assert.deepEqual(restored,s);assert.ok(C.exportCSV(restored).includes('cat-study'));
 const csv=C.exportCSV(restored);assert.equal(C.importCSV(csv,restored).find(x=>x.type==='expense').category,'cat-study');
 assert.match(C.importCSV(csv,base()).find(x=>x.error)?.error||'',/分类不存在/);
});

test('invalid and duplicate custom categories cannot corrupt an existing ledger or a backup',()=>{
 const s=change(base(),'CATEGORY',{id:'cat-study',name:'学习'});
 for(const value of [{id:'food',name:'假餐饮'},{id:'cat-x',name:'学习'},{id:'cat-x',name:' 餐饮 '},{id:'cat-x',name:''},{id:'__proto__',name:'无效'}])assert.throws(()=>change(s,'CATEGORY',value));
 const bad=structuredClone(s);bad.categories.push({...bad.categories[0]});assert.throws(()=>C.restore(C.backup(bad)));
 const old=base();delete old.categories;assert.deepEqual(C.restore(C.backup(old)),old);
 assert.throws(()=>change(s,'POST',{...expense(100),splits:[{amount:100,category:'__proto__',budgetId:'food'}]}));
});
