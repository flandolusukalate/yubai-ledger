import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {draftRule,validateRules,validateRuleRegion,rulesAfterRestore} from '../app/src/main/assets/web/rule-model.mjs';
const now='2026-11-01T04:00:00Z',hash='a'.repeat(64),windowClass='com.tencent.mm.framework.app.UIPageFragmentActivity';
const rule=()=>({...draftRule('r1'),enabled:true,testedHash:hash,windowClass,fields:{amount:{x:.3,y:.3,w:.3,h:.06},success:{x:.3,y:.1,w:.3,h:.04}}});
function base(){let s=C.reduce(C.initialState(),{type:'PLAN',value:{month:'2026-11',income:300000,savings:60000,emergency:15000,startDay:1,budgets:[['food',105000,true],['transport',15000,true],['fun',30000,true],['goods',15000,true],['trip',30000,false],['fixed',30000,false]].map(([id,allocated,daily])=>({id,name:id,allocated,daily,floor:0}))}});return C.reduce(s,{type:'SETTINGS',value:{sources:['com.tencent.mm'],pageCapture:true,pageOcr:true,customRules:[rule()]}});}
const candidate=(overrides={})=>({id:C.uid(),fingerprint:C.uid(),source:'accessibility',captureMethod:'accessibility-window-ocr',origin:'wechat',evidence:'wechat-custom-paid-page-v1',amount:2000,type:'expense',direction:'out',fundsStatus:'paid_out',status:'pending',successful:true,reliable:false,merchant:'测试店',reference:'',paymentEvidenceKey:'b'.repeat(64),occurredAt:now,collectedAt:now,customRuleId:'r1',customRuleRevision:1,customRuleHash:hash,customWindow:windowClass,...overrides});
const sum=s=>C.summary(s,'2026-11','2026-11-01');

test('oversized success marking reports the field and recovery without relaxing the limit',()=>{
 assert.throws(()=>validateRuleRegion({x:.04224,y:.39531,w:.91941,h:.50022},'success'),/成功状态.*范围过大.*文字列表/);
 assert.doesNotThrow(()=>validateRuleRegion({x:.29717,y:.61508,w:.16667,h:.01768},'success'));
 assert.throws(()=>validateRuleRegion({x:0,y:0,w:1,h:.281},'amount'),/实付金额.*范围过大/);
 assert.throws(()=>validateRuleRegion({x:0,y:0,w:NaN,h:.02},'merchant'),/商户.*标记无效/);
});

test('adaptive locator schema, edit invalidation and restore retain learning but require fresh proofs',()=>{
 const learned={...rule(),adaptive:true,locators:{amount:'paid-label',success:'success'}};
 assert.doesNotThrow(()=>validateRules([learned]));
 for(const bad of [{adaptive:'yes'},{locators:{amount:'largest-number',success:'success'}},{locators:{}},{locators:{amount:'paid-label',success:'success',text:'private OCR'}},{locators:{amount:'paid-label',success:'success',merchant:'merchant-below'}}])assert.throws(()=>validateRules([{...learned,...bad}]));
 const restored=rulesAfterRestore([learned])[0];assert.deepEqual(restored.locators,learned.locators);assert.equal(restored.enabled,false);assert.equal(restored.testedHash,'');assert.equal(restored.windowClass,'');
});

test('uncertain adaptive receipt never debits; repeated evidence and later complete ID retain one payment',()=>{
 const uncertain=candidate({amount:null,successful:false,evidence:'wechat-custom-review-page-v1',reference:'wechat:uncertain-001',fingerprint:'review001'});
 let s=C.reduce(base(),{type:'INGEST',value:[uncertain,uncertain]});assert.equal(s.candidates.length,1);assert.equal(sum(s).availableToday,5500);assert.equal(sum(s).pendingPaymentTotal,0);
 const complete=candidate({reference:uncertain.reference});s=C.reduce(s,{type:'INGEST',value:complete});assert.equal(sum(s).availableToday,3500);
 s=C.reduce(s,{type:'INGEST',value:{...complete,id:C.uid(),fingerprint:C.uid()}});assert.equal(sum(s).availableToday,3500);
});

test('legacy and learned rules observing the same transaction share one hold',()=>{
 const learned={...rule(),id:'learned',testedHash:'f'.repeat(64),adaptive:true,locators:{amount:'paid-label',success:'success'}};
 let s=C.reduce(base(),{type:'SETTINGS',value:{customRules:[rule(),learned]}});
 const first=candidate({reference:'wechat:shared-semantic-001'}),again=candidate({reference:first.reference,customRuleId:learned.id,customRuleHash:learned.testedHash});
 s=C.reduce(s,{type:'INGEST',value:[first,again,again]});assert.equal(sum(s).availableToday,3500);assert.equal(sum(s).pendingPaymentTotal,2000);
 assert.equal(C.summary(C.restore(C.backup(s)),'2026-11','2026-11-01').availableToday,3500);
});
test('template creation, preview metadata and configuration never write payments or change allowance',()=>{const s=base();assert.equal(sum(s).availableToday,5500);assert.equal(s.candidates.length+s.transactions.length,0);assert.doesNotThrow(()=>validateRules([draftRule('draft')]));});
test('approved custom payment uses existing 55→35 hold and purpose confirmation does not charge again',()=>{const c=candidate();let s=C.reduce(base(),{type:'INGEST',value:c});assert.equal(sum(s).availableToday,3500);assert.equal(s.transactions.length,0);s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{type:'expense',amount:2000,accountId:'cash',occurredAt:now,splits:[{category:'food',budgetId:'food',amount:2000}]}}});assert.equal(sum(s).availableToday,3500);assert.equal(sum(s).expenses,2000);});
test('untested, wrong revision/window/hash, incoming, failed, screenshot and disabled sources cannot auto-debit',()=>{
 for(const changes of [{customRuleId:'absent'},{customRuleRevision:2},{customRuleHash:'c'.repeat(64)},{customWindow:'com.tencent.mm.ui.LauncherUI'},{direction:'in'},{successful:false},{source:'ocr'},{amount:null},{type:'refund'},{fundsStatus:'unknown'}])assert.equal(sum(C.reduce(base(),{type:'INGEST',value:candidate(changes)})).pendingPaymentTotal,0,JSON.stringify(changes));
 for(const settings of [{customRules:[{...rule(),enabled:false}]},{pageOcr:false},{pageCapture:false},{autoRecordPayments:false},{sources:[]}]){const s=C.reduce(base(),{type:'SETTINGS',value:settings});assert.equal(sum(C.reduce(s,{type:'INGEST',value:candidate()})).pendingPaymentTotal,0);}
});
test('same ID across notification/page, repeated screenshot evidence and restart do not duplicate holds',()=>{
 const c=candidate({reference:'wechat:testtransaction001'});let s=C.reduce(base(),{type:'INGEST',value:[c,c,candidate({reference:c.reference,source:'notification',reliable:true})]});assert.equal(sum(s).pendingPaymentTotal,2000);
 s=C.restore(C.backup(s));s=C.reduce(s,{type:'INGEST',value:candidate({reference:c.reference,occurredAt:'2026-11-01T08:00:00Z'})});assert.equal(sum(s).pendingPaymentTotal,2000);
 let noId=C.reduce(base(),{type:'INGEST',value:[candidate(),candidate({occurredAt:'2026-11-01T08:00:00Z'})]});assert.equal(sum(noId).pendingPaymentTotal,2000);assert.ok(noId.candidates[1].suspectedCandidateId);
});
test('two different 1-yuan payments with distinct IDs stay distinct; no-ID same-amount remains a visible question',()=>{
 let s=C.reduce(base(),{type:'INGEST',value:[candidate({amount:100,reference:'wechat:distinct1001'}),candidate({amount:100,reference:'wechat:distinct1002'})]});assert.equal(sum(s).pendingPaymentTotal,200);assert.equal(sum(s).availableToday,5300);
 s=C.reduce(base(),{type:'INGEST',value:[candidate({amount:100}),candidate({amount:100})]});assert.equal(s.candidates.length,2);assert.equal(sum(s).pendingPaymentTotal,100);assert.ok(s.candidates[1].suspectedCandidateId);
});
test('deleting rules preserves existing evidence and backup relationships; restore configuration is disabled until re-test',()=>{let s=C.reduce(base(),{type:'INGEST',value:candidate()});s=C.reduce(s,{type:'SETTINGS',value:{customRules:[]}});assert.equal(sum(s).pendingPaymentTotal,2000);assert.equal(C.restore(C.backup(s)).candidates.length,1);const restored=rulesAfterRestore([rule()]);assert.equal(restored[0].enabled,false);assert.equal(restored[0].testedHash,'');assert.equal(restored[0].windowClass,'');});
test('malformed rectangles, private screenshot payload, unsupported app, duplicate IDs and forged approval are rejected',()=>{for(const bad of [{...rule(),image:'private'},{...rule(),origin:'alipay'},{...rule(),fields:{amount:{x:0,y:0,w:1,h:1}}},{...rule(),testedHash:''},{...rule(),windowClass:'com.tencent.mm.ui.chatting.ChattingUI'}])assert.throws(()=>C.reduce(base(),{type:'SETTINGS',value:{customRules:[bad]}}));assert.throws(()=>validateRules([rule(),rule()]));});

test('instant receipt and later bill with different merchant labels share a conservative hold without guessing identity',()=>{
 const common={amount:200,paymentPageKey:'d'.repeat(64)};
 const instant=candidate({...common,merchant:'测试商户（**王）',paymentEvidenceKey:'1'.repeat(64)});
 const detail=candidate({...common,merchant:'扫二维码付款-测试商户',reference:'wechat:actualdetail0001',occurredAt:'2026-11-01T03:30:00Z',paymentEvidenceKey:'2'.repeat(64)});
 let s=C.reduce(base(),{type:'INGEST',value:instant});assert.equal(sum(s).availableToday,5300);
 s=C.reduce(s,{type:'INGEST',value:detail});assert.equal(sum(s).pendingPaymentTotal,200);assert.equal(sum(s).availableToday,5300);
 assert.equal(s.candidates.find(c=>c.id===detail.id).suspectedCandidateId,instant.id);assert.equal(s.candidates.length,2,'Both pieces of evidence remain reviewable');
 s=C.restore(C.backup(s));s=C.reduce(s,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid()}});assert.equal(sum(s).pendingPaymentTotal,200);
});

test('shared cross-page hint never collapses two distinct reliable WeChat transaction IDs',()=>{
 const common={amount:200,paymentPageKey:'d'.repeat(64)};
 const s=C.reduce(base(),{type:'INGEST',value:[candidate({...common,reference:'wechat:independent0001'}),candidate({...common,reference:'wechat:independent0002'})]});
 assert.equal(sum(s).pendingPaymentTotal,400);assert.equal(s.candidates.filter(c=>c.suspectedCandidateId).length,0);
});

test('OCR letter loss and similar Chinese glyphs across result/detail are reviewable, without a second hold',()=>{
 const instant=candidate({amount:200,merchant:'ABQ_测试晴(**王)',timeBasis:'page-observed-time',occurredAt:now,collectedAt:now});
 const detail=candidate({amount:200,merchant:'扫二维码付款-给AQ_测试睛',timeBasis:'page-local-time-book-zone',reference:'wechat:actualocr0001',occurredAt:'2026-11-01T03:45:00Z',collectedAt:'2026-11-01T04:01:00Z',paymentEvidenceKey:'e'.repeat(64)});
 let s=C.reduce(base(),{type:'INGEST',value:[instant,detail]});assert.equal(sum(s).pendingPaymentTotal,200);assert.equal(s.candidates[1].suspectedCandidateId,instant.id);
 // A fresh real revisit repairs an unresolved pre-upgrade double hold through the same ingestion path.
 s=base();s.candidates=[{...instant,autoRecorded:true},{...detail,autoRecorded:true}];assert.equal(sum(s).pendingPaymentTotal,400);
 s=C.reduce(s,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),collectedAt:'2026-11-01T06:00:00Z'}});assert.equal(sum(s).pendingPaymentTotal,200);assert.equal(s.transactions.length,0);
 s=C.reduce(C.restore(C.backup(s)),{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),collectedAt:'2026-11-01T07:00:00Z'}});assert.equal(sum(s).pendingPaymentTotal,200,'Revisiting same ID retains original capture evidence after restart');
});

test('legacy wrapped prefix is only a review hint; distinct complete suffixes still create distinct holds',()=>{
 const old=candidate({amount:200,reference:'wechat:100010730120261103012345678901234'});
 const full=candidate({amount:200,reference:old.reference+'3',referenceLayout:'bounded-lines-v1'});
 let s=C.reduce(base(),{type:'INGEST',value:[old,full]});assert.equal(sum(s).pendingPaymentTotal,200);assert.equal(s.candidates[1].suspectedCandidateId,old.id);
 s=C.reduce(s,{type:'INGEST',value:candidate({...full,id:C.uid(),fingerprint:C.uid(),reference:old.reference+'4'})});assert.equal(sum(s).pendingPaymentTotal,400,'Different complete IDs must not share the legacy prefix hold');
 const differentTime={...full,id:C.uid(),fingerprint:C.uid(),occurredAt:'2026-11-01T04:01:00Z'};
 assert.equal(sum(C.reduce(base(),{type:'INGEST',value:[old,differentTime]})).pendingPaymentTotal,400);
});

test('late bill with a near-exact payment time and minor OCR name variation does not debit twice',()=>{
 const instant=candidate({amount:100,merchant:'ABQ 云晚睛(**王)',timeBasis:'page-observed-time',occurredAt:'2026-11-01T04:00:03Z',collectedAt:'2026-11-01T04:00:03Z'});
 const detail=candidate({amount:100,merchant:'扫二维码付款-给AQ_云晚晴',timeBasis:'page-local-time-book-zone',reference:'wechat:late-detail-001',referenceLayout:'bounded-lines-v1',occurredAt:now,collectedAt:'2026-11-01T04:30:00Z',paymentEvidenceKey:'e'.repeat(64)});
 let s=C.reduce(base(),{type:'INGEST',value:[instant,detail]});
 assert.equal(sum(s).pendingPaymentTotal,100);assert.equal(s.candidates[1].suspectedCandidateId,instant.id);
 assert.equal(s.candidates[1].status,'pending','Similarity is a review hint, not a confirmed merge');
 s=C.reduce(C.restore(C.backup(s)),{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),collectedAt:'2026-11-02T04:30:00Z'}});
 assert.equal(sum(s).pendingPaymentTotal,100);
 s=C.reduce(s,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),reference:'wechat:distinct-detail-002'}});
 assert.equal(sum(s).pendingPaymentTotal,200,'A different complete ID still records the second actual payment');
 let dismissed=C.reduce(base(),{type:'INGEST',value:instant});dismissed=C.reduce(dismissed,{type:'DISMISS',value:instant.id});
 dismissed=C.reduce(dismissed,{type:'INGEST',value:detail});assert.equal(sum(dismissed).pendingPaymentTotal,100);assert.equal(dismissed.candidates[0].status,'dismissed');
 let old=base();old.candidates=[{...instant,autoRecorded:true},{...detail,autoRecorded:true}];
 assert.equal(sum(old).pendingPaymentTotal,200);
 old=C.reduce(old,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),collectedAt:'2026-11-01T05:00:00Z'}});
 assert.equal(sum(old).pendingPaymentTotal,100,'Fresh native evidence repairs a pre-fix double hold without erasing either source');
 for(const patch of [{occurredAt:'2026-11-01T03:59:00Z'},{merchant:'扫二维码付款-给完全不同商户'}]){
  const other=C.reduce(base(),{type:'INGEST',value:[instant,{...detail,...patch}]});assert.equal(sum(other).pendingPaymentTotal,200);
 }
});

test('short counterparty with one OCR glyph error and a three-second payment gap is a duplicate hint',()=>{
 const instant=candidate({amount:100,merchant:'云晚晴(**王)',timeBasis:'page-observed-time',occurredAt:'2026-11-01T04:00:03Z',collectedAt:'2026-11-01T04:00:03Z'});
 const detail=candidate({amount:100,merchant:'扫二维码付款-给云晚睛',timeBasis:'page-local-time-book-zone',reference:'wechat:short-name-bill-1',referenceLayout:'bounded-lines-v1',occurredAt:now,collectedAt:'2026-11-01T04:30:00Z',paymentEvidenceKey:'e'.repeat(64)});
 let s=C.reduce(base(),{type:'INGEST',value:[instant,detail]});assert.equal(sum(s).pendingPaymentTotal,100);assert.equal(s.candidates[1].suspectedCandidateId,instant.id);assert.equal(s.candidates[1].status,'pending');
 s=C.reduce(C.restore(C.backup(s)),{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid()}});assert.equal(sum(s).pendingPaymentTotal,100);
 s=C.reduce(s,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),reference:'wechat:short-name-bill-2'}});assert.equal(sum(s).pendingPaymentTotal,200);
 for(const patch of [{merchant:'扫二维码付款-给其他店'},{occurredAt:'2026-11-01T03:59:00Z'},{direction:'in'}]){
  assert.equal(C.reduce(base(),{type:'INGEST',value:[instant,{...detail,...patch}]}).candidates[1].suspectedCandidateId,undefined);
 }
 let old=base();old.candidates=[{...instant,autoRecorded:true},{...detail,autoRecorded:true}];
 old=C.reduce(old,{type:'INGEST',value:{...detail,id:C.uid(),fingerprint:C.uid(),collectedAt:'2026-11-01T05:00:00Z'}});assert.equal(sum(old).pendingPaymentTotal,100);
});

test('a no-ID success page cannot hide a second distinct bill ID through a shared similarity group',()=>{
 const instant=candidate({amount:200,merchant:'测试商户（**王）'});
 const a=candidate({amount:200,merchant:'扫二维码付款-测试商户',reference:'wechat:complete0001',referenceLayout:'bounded-lines-v1'});
 const b=candidate({...a,id:C.uid(),fingerprint:C.uid(),reference:'wechat:complete0002'});
 let s=C.reduce(base(),{type:'INGEST',value:[instant,a,b]});assert.equal(sum(s).pendingPaymentTotal,400);
 s=C.reduce(s,{type:'INGEST',value:{...b,id:C.uid(),fingerprint:C.uid()}});assert.equal(sum(s).pendingPaymentTotal,400);
});
