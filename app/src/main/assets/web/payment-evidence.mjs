// Counterparty similarity is only a review hint. Never treat it as a transaction ID.
export function paymentCounterparty(merchant) {
  return String(merchant||'').slice(0,120).normalize('NFKC').replace(/\s+/g,'')
    .replace(/^(?:扫二维码付款|扫码付款)[-:：]给?/,'')
    .replace(/\(\*{1,8}[^()]{0,8}\)$/,'');
}
function distance(a,b,limit) {
  if(Math.abs(a.length-b.length)>limit)return limit+1;
  let row=Array.from({length:b.length+1},(_,i)=>i);
  for(let i=1;i<=a.length;i++){
    const next=[i];
    for(let j=1;j<=b.length;j++)next[j]=Math.min(next[j-1]+1,row[j]+1,row[j-1]+(a[i-1]===b[j-1]?0:1));
    if(Math.min(...next)>limit)return limit+1;
    row=next;
  }
  return row[b.length];
}
export function similarPaymentPages(a,b) {
  const withAi=[a,b].some(c=>c.captureMethod==='accessibility-window-ai');
  const eligible=c=>{
    if(c.source!=='accessibility'||c.successful!==true||c.direction!=='out'||c.fundsStatus!=='paid_out')return false;
    if(c.captureMethod==='accessibility-window-ai')return c.evidence===`${c.origin}-ai-paid-page-v1`;
    if(c.captureMethod!=='accessibility-window-ocr')return false;
    if(c.origin==='wechat')return c.evidence==='wechat-custom-paid-page-v1'||withAi&&['wechat-paid-merchant-result-v1','wechat-paid-transfer-result-v1','wechat-paid-transfer-pending-detail-v1'].includes(c.evidence);
    return c.origin==='alipay'&&(c.evidence==='alipay-paid-page-v1'||withAi&&c.evidence==='alipay-custom-paid-page-v1');
  };
  if(a.origin!==b.origin||![a,b].every(eligible)||a.amount!==b.amount)return false;
  const left=paymentCounterparty(a.merchant),right=paymentCounterparty(b.merchant);
  if(!left||!right)return false;
  if(left===right)return true;
  // Only tolerate a small OCR variation across two different layout/time roles,
  // observed close together. Do not fuzzy-match two bill IDs or two success pages.
  const observed=[a,b].find(c=>c.timeBasis==='page-observed-time'&&!c.reference);
  const dated=[a,b].find(c=>c.timeBasis==='page-local-time-book-zone'&&c.reference);
  if(!observed||!dated)return false;
  const capturedGap=Math.abs(Date.parse(a.firstCollectedAt||a.collectedAt)-Date.parse(b.firstCollectedAt||b.collectedAt));
  const paidGap=Date.parse(observed.occurredAt)-Date.parse(dated.occurredAt);
  // A bill may be opened much later than its payment. A tight original-time
  // match permits a conservative duplicate hint despite small OCR name errors.
  // Separators can disappear in OCR. This path never merges records or IDs.
  const compactLeft=left.replace(/_/g,''),compactRight=right.replace(/_/g,'');
  const shortest=Math.min(compactLeft.length,compactRight.length);
  const edits=shortest>=5?2:shortest>=3?1:0;
  if(Number.isFinite(capturedGap)&&paidGap>=0&&paidGap<=15000&&edits>0&&distance(compactLeft,compactRight,edits)<=edits)return true;
  if(Math.min(left.length,right.length)<6)return false;
  if(!(capturedGap<=5*60000&&paidGap>=-60000&&paidGap<=30*60000))return false;
  return distance(left,right,2)<=2;
}

// Pre-0.2.17 custom OCR could stop at a wrapped identifier's first line. This
// upgrade-only hint never merges IDs, and cannot hide a second complete ID.
export function legacyReferenceContinuation(a,b,others) {
  const old=[a,b].find(c=>c.reference&&!c.referenceLayout);
  const complete=[a,b].find(c=>c.reference&&c.referenceLayout==='bounded-lines-v1');
  if(!old||!complete||![old,complete].every(c=>c.evidence==='wechat-custom-paid-page-v1'&&c.origin==='wechat'&&c.source==='accessibility'&&c.successful&&c.direction==='out'))return false;
  if(old.amount!==complete.amount||old.occurredAt!==complete.occurredAt||!complete.reference.startsWith(old.reference))return false;
  const tail=complete.reference.length-old.reference.length;
  if(tail<1||tail>4||others.some(c=>c.referenceLayout==='bounded-lines-v1'&&c.reference!==complete.reference&&c.reference?.startsWith(old.reference)))return false;
  const left=paymentCounterparty(old.merchant),right=paymentCounterparty(complete.merchant);
  return !!left&&!!right&&(left===right||Math.min(left.length,right.length)>=6&&distance(left,right,2)<=2);
}

export function paymentIdentityGuard(candidates,incoming) {
  if(!incoming.reference)return ()=>false;
  const byId=new Map(candidates.map(c=>[c.id,c])),memo=new Map();
  const group=c=>{
    const seen=new Set(),path=[];
    while(c&&!memo.has(c.id)&&!seen.has(c.id)){
      seen.add(c.id);path.push(c.id);
      const parent=byId.get(c.duplicateOf||c.suspectedCandidateId);
      if(!parent)break;c=parent;
    }
    const root=c?(memo.get(c.id)||c.transactionId||c.id):'';
    for(const id of path)memo.set(id,root);return root;
  };
  const conflicting=new Set();
  for(const c of candidates){
    if(!c.reference||c.reference===incoming.reference||c.reference.split(':')[0]!==incoming.reference.split(':')[0])continue;
    // The old parser's wrapped prefix is explicitly uncertain during this upgrade.
    if(!c.referenceLayout&&incoming.referenceLayout==='bounded-lines-v1'&&incoming.reference.startsWith(c.reference)&&incoming.reference.length-c.reference.length<=4)continue;
    conflicting.add(group(c));
  }
  return prior=>conflicting.has(group(prior));
}
