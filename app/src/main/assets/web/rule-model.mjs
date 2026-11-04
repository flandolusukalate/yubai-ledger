// Declarative configuration only. OCR and local test attestations belong to the Android collector.
export const MAX_RULES=12;
export function validateRuleRegion(b,field){
 const label={amount:'实付金额',merchant:'商户 / 收款方',success:'成功状态'}[field]||'字段';
 if(!b||Object.keys(b).sort().join(',')!=='h,w,x,y'||Object.values(b).some(v=>!Number.isFinite(v))||b.x<0||b.y<0||b.w<.002||b.h<.002||b.x+b.w>1.001||b.y+b.h>1.001)throw new Error(`${label}标记无效，请重新选择截图中的文字`);
 if(b.w*b.h>.28)throw new Error(`${label}标记范围过大，请只选择对应文字；也可从下方文字列表选择`);
 return b;
}
export function validateRules(rules){
 if(!Array.isArray(rules)||rules.length>MAX_RULES)throw new Error('最多保存12条自定义付款规则');
 const ids=new Set(),keys=new Set(['id','version','revision','name','origin','enabled','aspect','fields','testedHash','windowClass','matchText','adaptive','locators']);
 for(const r of rules){
  if(!r||Object.keys(r).some(k=>!keys.has(k))||r.version!==1||!/^[A-Za-z0-9_-]{1,80}$/.test(r.id||'')||ids.has(r.id)||!Number.isInteger(r.revision)||r.revision<1||r.revision>1000000||!['wechat','alipay'].includes(r.origin)||typeof r.enabled!=='boolean'||typeof r.name!=='string'||!r.name.trim()||r.name.length>50||!Number.isFinite(r.aspect)||r.aspect<.25||r.aspect>2.5)throw new Error('自定义规则格式或版本无效');
  if(r.matchText!==undefined&&(typeof r.matchText!=='string'||r.matchText.length>40||/[\p{Cc}\p{Cf}]/u.test(r.matchText)))throw new Error('页面识别词最多40字');
  if(r.adaptive!==undefined&&typeof r.adaptive!=='boolean')throw new Error('定位模式无效');
  const locatorKinds={amount:['paid-label','outgoing-amount'],success:['success'],merchant:['merchant-label','merchant-above','merchant-below']};
  if(r.locators!==undefined&&(!r.locators||typeof r.locators!=='object'||Array.isArray(r.locators)||Object.entries(r.locators).some(([key,kind])=>!r.adaptive||!r.fields?.[key]||!Object.hasOwn(locatorKinds,key)||!locatorKinds[key].includes(kind))))throw new Error('字段定位规则无效');
  ids.add(r.id);
  if(!r.fields||Array.isArray(r.fields)||Object.keys(r.fields).some(k=>!['amount','merchant','success'].includes(k)))throw new Error('规则字段无效');
  for(const field of ['amount','merchant','success']){
   const b=r.fields[field];if(b==null){if(r.enabled&&field!=='merchant')throw new Error('启用前请标记金额和成功状态');continue;}
   validateRuleRegion(b,field);
   if(r.adaptive&&r.enabled&&!r.locators?.[field])throw new Error('字段尚未学习，请重新测试截图');
  }
  if(typeof r.testedHash!=='string'||r.testedHash&&!/^[a-f0-9]{64}$/.test(r.testedHash)||typeof r.windowClass!=='string'||r.windowClass&&!allowedWindow(r.windowClass,r.origin))throw new Error('规则测试或窗口信息无效');
  if(r.enabled&&(!r.testedHash||!r.windowClass))throw new Error('请先测试截图并绑定真实窗口');
 }
 return rules;
}
export function allowedWindow(name,origin='wechat'){return typeof name==='string'&&(origin==='wechat'?name.startsWith('com.tencent.mm.'):origin==='alipay'&&name.startsWith('com.alipay.'))&&name.length<240&&/^[A-Za-z0-9_.$]+$/.test(name)&&!/(LauncherUI|LauncherActivity|chatting|password|passwd|pwd|fingerprint|WalletPayUI|WalletCheckPwd|login|settings|contact|gallery|ImagePreview|ImageGallery)/i.test(name);}
export function approvedCustomPayment(settings,c){
 if(!['wechat','alipay'].includes(c.origin)||c.evidence!==`${c.origin}-custom-paid-page-v1`||c.type!=='expense'||c.captureMethod!=='accessibility-window-ocr')return false;
 const r=settings.customRules?.find(r=>r.id===c.customRuleId);
 return !!(r?.enabled&&r.origin===c.origin&&r.revision===c.customRuleRevision&&r.testedHash&&r.testedHash===c.customRuleHash&&r.windowClass&&r.windowClass===c.customWindow);
}
export function draftRule(id){return {id,version:1,revision:1,name:'新的付款页面',origin:'wechat',enabled:false,aspect:.46,fields:{},testedHash:'',windowClass:''};}
export function rulesAfterRestore(rules=[]){return rules.map(r=>({...r,enabled:false,testedHash:'',windowClass:''}));}
export function screenshotPayment(candidate,{imageKey,origin,now=new Date().toISOString()}){
 if(!/^[a-f0-9]{64}$/.test(imageKey||'')||!['wechat','alipay'].includes(origin))throw new Error('截图已失效，请重新选择');
 const c=candidate||{};
 return {id:crypto.randomUUID(),fingerprint:'ocr-image:'+imageKey,source:'ocr',origin,type:'expense',amount:c.amount??null,merchant:c.merchant||'',reference:c.reference||'',paymentEvidenceKey:c.paymentEvidenceKey||'',occurredAt:c.occurredAt||now,collectedAt:now,status:'pending',successful:c.successful===true,reliable:false,timeBasis:c.timeBasis||'screenshot-observed-time',...(c.captureMethod==='deepseek-fallback'?{captureMethod:'deepseek-fallback',type:c.type||'complex'}:{}),reason:c.captureMethod==='deepseek-fallback'?c.reason:'截图补记：请核对实付金额、成功状态、实际日期与承担预算。保存规则不会自动入账。'};
}
