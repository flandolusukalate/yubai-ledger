package cn.yubai.ledger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.concurrent.TimeUnit;

/** Isolated proof store and synthetic ledger; never writes the personal application's ledger. */
public final class CustomRuleVerificationRunner extends Instrumentation {
    private int checks;private Bundle args;
    private void check(boolean b,String label){if(!b)throw new AssertionError(label);checks++;}
    private interface Checked {void run() throws Exception;}
    private void rejects(Checked action,String label) throws Exception {boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,label);}
    static JSONObject rect(double x,double y,double w,double h)throws Exception{return new JSONObject().put("x",x).put("y",y).put("w",w).put("h",h);}
    static JSONObject line(String text,double y)throws Exception{return rect(.2,y,.6,.025).put("text",text);}
    private JSONObject doc(String amount,String status) throws Exception {return new JSONObject().put("width",1000).put("height",2200).put("lines",new JSONArray().put(line(status,.10)).put(line("测试商户",.23)).put(line(amount,.35)).put(line("完成",.80)));}
    private JSONObject rule() throws Exception{return new JSONObject().put("id","test-rule").put("version",1).put("revision",1).put("name","测试规则").put("origin","wechat").put("enabled",false).put("aspect",1000.0/2200).put("fields",new JSONObject().put("success",rect(.2,.10,.6,.025)).put("merchant",rect(.2,.23,.6,.025)).put("amount",rect(.2,.35,.6,.025))).put("testedHash","").put("windowClass","");}
    private JSONObject evaluate(JSONObject rule,JSONObject doc,long now,String session)throws Exception{return CustomPaymentRule.evaluate(rule,doc,now,session,java.time.ZoneId.of("Asia/Shanghai"));}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle output=new Bundle();try{
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"test runner is for a dedicated emulator");
        checks+=AdaptiveRuleChecks.run();
        Context context=new ContextWrapper(getTargetContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("custom-rule-test-"+name,mode);}
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath("custom-rule-test-"+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("custom-rule-test-"+name,mode,factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return super.openOrCreateDatabase("custom-rule-test-"+name,mode,factory,handler);}
        };
        CustomRuleRegistry.resetProofs(context);
        long now=java.time.Instant.parse("2026-11-03T08:00:00Z").toEpochMilli();
        JSONObject r=rule(),d=doc("¥20.00","支付成功"),c=evaluate(r,d,now,"one");
        check(c.getLong("amount")==2000&&c.getString("merchant").equals("测试商户"),"selected amount and merchant are interpreted by production parser");
        check(c.getString("direction").equals("out")&&c.getString("source").equals("accessibility"),"candidate source and direction");
        JSONObject closeRows=doc("¥20.00","支付成功");closeRows.getJSONArray("lines").put(line("收款方备注",.13));
        check(evaluate(r,closeRows,now,"rows").getLong("amount")==2000,"adjacent bill row does not contaminate an exact selected status line");
        JSONObject mini=doc("实付金额 ¥20.00","支付成功");mini.getJSONArray("lines").getJSONObject(1).put("text","麦当劳");mini.getJSONArray("lines").getJSONObject(3).put("text","查看订单");
        JSONObject miniRule=rule().put("matchText","麦当劳");
        check(evaluate(miniRule,mini,now,"mini").getLong("amount")==2000,"explicit paid amount and exact merchant anchor recognize a mini-app result without Done");
        JSONObject ocrRole=new JSONObject(mini.toString());ocrRole.getJSONArray("lines").getJSONObject(2).put("text","实付全额¥20.00");
        check(evaluate(miniRule,ocrRole,now,"ocr-role").getLong("amount")==2000,"actual ML Kit paid-label variant preserves the amount without digit correction");
        rejects(()->evaluate(rule(),mini,now,"no-anchor"),"mini-app structure without identifying anchor cannot activate");
        rejects(()->evaluate(rule().put("matchText","另一家店"),mini,now,"wrong-anchor"),"different mini-app merchant cannot match the learned page");
        JSONObject pendingMini=new JSONObject(mini.toString());pendingMini.getJSONArray("lines").getJSONObject(0).put("text","订单提交成功");
        rejects(()->evaluate(miniRule,pendingMini,now,"not-paid"),"order submission is not payment success");
        JSONObject missingRole=new JSONObject(mini.toString());missingRole.getJSONArray("lines").getJSONObject(2).put("text","¥20.00");
        rejects(()->evaluate(miniRule,missingRole,now,"role"),"mini-app unlabelled number cannot replace actual paid amount");
        JSONObject aliRule=new JSONObject(miniRule.toString()).put("origin","alipay").put("windowClass",AlipayCaptureWindow.BILL);
        JSONObject aliCandidate=evaluate(aliRule,mini,now,"alipay-mini");
        check(aliCandidate.getString("origin").equals("alipay")&&aliCandidate.getString("evidence").equals("alipay-custom-paid-page-v1"),"custom Alipay evidence keeps its own origin");
        check(aliCandidate.getString("paymentEvidenceKey").equals(AlipayPaymentPage.candidate(2000,"麦当劳","",java.time.Instant.ofEpochMilli(now).toString(),"page-observed-time",now,"built-in").getString("paymentEvidenceKey")),"custom and built-in Alipay share conservative content identity");
        rejects(()->CustomPaymentRule.validate(new JSONObject(aliRule.toString()).put("windowClass","com.tencent.mm.plugin.appbrand.ui.AppBrandUI")),"Alipay proof cannot bind a WeChat container");
        rejects(()->CustomPaymentRule.validate(new JSONObject(aliRule.toString()).put("windowClass","com.alipay.mobile.security.login.LoginActivity")),"sensitive Alipay windows remain excluded");
        JSONObject businessId=new JSONObject(mini.toString());businessId.getJSONArray("lines").put(line("订单号 2026110312345678901234567890",.6));
        check(evaluate(aliRule,businessId,now,"business-order").getString("reference").isEmpty(),"mini-app merchant order number is not a platform payment ID");
        check(!CustomPaymentRule.signature(miniRule).equals(CustomPaymentRule.signature(new JSONObject(miniRule.toString()).put("matchText","另一家店"))),"changing identifying text invalidates the test attestation");
        for(String bad:new String[]{"支付失败","支付未成功","处理中","收款成功","退款成功","交易成功"})rejects(()->evaluate(r,doc("¥20.00",bad),now,"bad"),"failed/incoming/uncertain status rejected");
        for(String bad:new String[]{"¥0.00","+20.00","¥20.001","余额 ¥20.00","¥20.00 ¥30.00"})rejects(()->evaluate(r,doc(bad,"支付成功"),now,"amount"),"invalid or conflicting amount rejected");
        JSONObject conflict=doc("¥20.00","支付成功");conflict.getJSONArray("lines").put(line("原价 ¥25.00",.50));rejects(()->evaluate(r,conflict,now,"original"),"other amount roles require explicit paid label");
        JSONObject explicit=doc("实付金额 ¥20.00","支付成功");explicit.getJSONArray("lines").put(line("原价 ¥25.00",.50));check(evaluate(r,explicit,now,"explicit").getLong("amount")==2000,"explicit paid amount excludes original price");
        JSONObject negative=doc("−1.00","支付成功");check(evaluate(r,negative,now,"negative").getLong("amount")==100,"negative outgoing amount preserved as positive integer fen");
        JSONObject bill=doc("−1.00","支付成功");bill.put("lines",new JSONArray().put(line("支付成功",.10)).put(line("−1.00",.35)).put(line("当前状态",.40)).put(line("全部账单",.43)).put(line("转账时间 2026年11月3日 12:38:26",.46)).put(line("转账单号 test00000001",.49)));
        JSONObject billResult=evaluate(r,bill,now,"bill");check(billResult.getString("occurredAt").equals("2026-11-03T04:38:26Z"),"bill uses ledger timezone instead of emulator UTC");
        JSONObject ocrBill=new JSONObject(bill.toString());ocrBill.getJSONArray("lines").getJSONObject(3).put("text","支付万式");
        check(evaluate(r,ocrBill,now,"bill-label").getLong("amount")==100,"observed OCR payment-method label variant still requires a complete outgoing bill");
        JSONObject incompleteBill=new JSONObject(ocrBill.toString());incompleteBill.getJSONArray("lines").getJSONObject(5).put("text","无交易标识");
        rejects(()->evaluate(r,incompleteBill,now,"incomplete-bill"),"OCR label compatibility does not waive transaction identity structure");
        JSONObject wrap=new JSONObject(bill.toString());JSONArray wrapLines=wrap.getJSONArray("lines");
        wrapLines.getJSONObject(wrapLines.length()-1).put("text","转账单号").put("x",.08).put("w",.15);
        wrapLines.put(line("100010730120261103012345678901234",.49).put("x",.30));
        wrapLines.put(line("3",.52).put("x",.30));
        String ref=evaluate(r,wrap,now,"wrap").getString("reference");
        check(ref.equals("wechat:1000107301202611030123456789012343"),"wrapped final digit is part of the transaction identifier");
        wrapLines.getJSONObject(wrapLines.length()-1).put("text","4");check(!evaluate(r,wrap,now,"wrap-other").getString("reference").equals(ref),"different final digits remain different transactions");
        wrapLines.getJSONObject(wrapLines.length()-1).put("y",.8);check(evaluate(r,wrap,now,"far").getString("reference").endsWith("1234"),"distant unrelated numeric text is not appended");
        JSONObject extraRect=rule();extraRect.getJSONObject("fields").getJSONObject("amount").put("text","private");rejects(()->CustomPaymentRule.validate(extraRect),"region cannot persist OCR text");
        rejects(()->CustomRuleRegistry.rules("{\"settings\":{\"customRules\":{}}}"),"native schema rejects non-list rules");
        JSONObject shifted=new JSONObject(d.toString()).put("width",2200).put("height",1000);rejects(()->evaluate(r,shifted,now,"aspect"),"layout change rejected");
        check(!WechatCaptureWindow.allowed("com.tencent.mm.framework.app.UIPageFragmentActivity"),"reported missed receipt uses container absent from old built-in whitelist");
        check(CustomPaymentRule.windowAllowed("com.tencent.mm.framework.app.UIPageFragmentActivity"),"container can be explicitly bound after tests");
        check(!CustomPaymentRule.windowAllowed("com.tencent.mm.ui.chatting.ChattingUI")&&!CustomPaymentRule.windowAllowed("com.tencent.mm.plugin.wallet.WalletPayUI"),"chat and input windows cannot be bound");
        rejects(()->CustomRuleRegistry.begin(context,r),"untested screenshot cannot start binding");
        r.put("testedHash",CustomRuleRegistry.recordTest(context,r));check(CustomRuleRegistry.tested(context,r),"screenshot proof is local");
        JSONObject ledger=new JSONObject().put("settings",new JSONObject().put("customRules",new JSONArray().put(r)));
        r.put("enabled",true);rejects(()->CustomRuleRegistry.validateCommit(context,ledger.toString()),"screenshot proof alone cannot enable automatic collection");r.put("enabled",false);
        CustomRuleRegistry.begin(context,r);CustomRuleRegistry.Binding binding=CustomRuleRegistry.binding();
        JSONObject bound=CustomRuleRegistry.finishBinding(context,binding,"com.tencent.mm.framework.app.UIPageFragmentActivity",d);
        check(bound.getLong("amount")==2000,"live preview same parser");r.put("windowClass","com.tencent.mm.framework.app.UIPageFragmentActivity").put("enabled",true);
        CustomRuleRegistry.validateCommit(context,ledger.toString());check(CustomRuleRegistry.active(context,ledger.toString()).length()==1,"only locally tested and bound rule is active");
        JSONObject edit=new JSONObject(r.toString()).put("name","修改名称");check(!CustomRuleRegistry.bound(context,edit),"edit invalidates previous proof");
        JSONObject wrongWindow=new JSONObject(r.toString()).put("windowClass","com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI");check(!CustomRuleRegistry.bound(context,wrongWindow),"window proof cannot be reused for another container");
        JSONObject restored=new JSONObject(CustomRuleRegistry.forRestore(ledger.toString())).getJSONObject("settings").getJSONArray("customRules").getJSONObject(0);check(!restored.getBoolean("enabled")&&restored.getString("testedHash").isEmpty()&&restored.getString("windowClass").isEmpty(),"restore retains configuration but drops activation");
        getTargetContext().deleteDatabase("custom-rule-test-yubai.db");
        try(Store isolatedStore=new Store(context)){
            JSONObject seed=AutoLedgerVerificationRunner.base();seed.getJSONObject("settings").put("pageOcr",true).put("customRules",new JSONArray().put(r));isolatedStore.save(seed.toString());PageCaptureService.syncSettings(context,seed.toString());
            JSONObject payment=evaluate(r,d,System.currentTimeMillis(),"worker-test");isolatedStore.enqueue(payment);
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,done::countDown);
            check(done.await(40,TimeUnit.SECONDS),"background custom payment processing completes");
            JSONObject saved=new JSONObject(isolatedStore.load());check(saved.getJSONArray("candidates").length()==1&&saved.getJSONArray("candidates").getJSONObject(0).optBoolean("autoRecorded")&&saved.getJSONArray("transactions").length()==0,"tested custom OCR uses same background reducer and pending payment hold");
            isolatedStore.enqueue(payment);java.util.concurrent.CountDownLatch repeated=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,repeated::countDown);
            check(repeated.await(40,TimeUnit.SECONDS)&&new JSONObject(isolatedStore.load()).getJSONArray("candidates").length()==1&&new JSONArray(isolatedStore.inbox()).length()==0,"background retry does not duplicate custom payment");
        }finally{getTargetContext().deleteDatabase("custom-rule-test-yubai.db");}
        JSONObject alipayBound=new JSONObject(aliRule.toString()).put("id","alipay-mini-test");
        alipayBound.put("testedHash",CustomRuleRegistry.recordTest(context,alipayBound));CustomRuleRegistry.begin(context,alipayBound);
        CustomRuleRegistry.finishBinding(context,CustomRuleRegistry.binding(),AlipayCaptureWindow.BILL,mini);alipayBound.put("enabled",true);
        check(CustomRuleRegistry.bound(context,alipayBound),"Alipay mini-app has its own local test and window proof");
        try(Store isolatedStore=new Store(context)){
            JSONObject seed=AutoLedgerVerificationRunner.base();seed.getJSONObject("settings").put("pageOcr",true).put("alipayPageCapture",true).put("sources",new JSONArray().put(AlipayCaptureWindow.PKG)).put("customRules",new JSONArray().put(alipayBound));isolatedStore.save(seed.toString());PageCaptureService.syncSettings(context,seed.toString());
            JSONObject payment=evaluate(alipayBound,mini,System.currentTimeMillis(),"alipay-background");isolatedStore.enqueue(payment);
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,done::countDown);
            check(done.await(40,TimeUnit.SECONDS),"Alipay custom background processing completes");
            JSONObject saved=new JSONObject(isolatedStore.load());check(saved.getJSONArray("candidates").length()==1&&saved.getJSONArray("candidates").getJSONObject(0).optBoolean("autoRecorded")&&saved.getJSONArray("transactions").length()==0,"Alipay custom OCR uses the actual background ledger hold without a fabricated expense");
            isolatedStore.enqueue(payment);java.util.concurrent.CountDownLatch repeated=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,repeated::countDown);
            check(repeated.await(40,TimeUnit.SECONDS)&&new JSONObject(isolatedStore.load()).getJSONArray("candidates").length()==1,"Alipay custom retry is idempotent");
        }finally{getTargetContext().deleteDatabase("custom-rule-test-yubai.db");}
        CustomRuleRegistry.resetProofs(context);check(!CustomRuleRegistry.bound(context,r),"backup cannot recreate local consent or test proofs");
        JSONObject adaptive=PaymentFieldLocator.learn(new JSONObject(miniRule.toString()).put("id","adaptive-bound").put("adaptive",true),mini);
        adaptive.put("testedHash",CustomRuleRegistry.recordTest(context,adaptive));CustomRuleRegistry.begin(context,adaptive);
        JSONObject movedMini=new JSONObject(mini.toString());for(int i=0;i<movedMini.getJSONArray("lines").length();i++){JSONObject l=movedMini.getJSONArray("lines").getJSONObject(i);l.put("y",l.getDouble("y")+.04);}
        CustomRuleRegistry.finishBinding(context,CustomRuleRegistry.binding(),"com.tencent.mm.plugin.appbrand.ui.AppBrandUI",movedMini);
        adaptive.put("windowClass","com.tencent.mm.plugin.appbrand.ui.AppBrandUI").put("enabled",true);
        check(CustomRuleRegistry.bound(context,adaptive),"adaptive local proof binds a moved real-window-shaped document");
        try(Store isolatedStore=new Store(context)){
            JSONObject seed=AutoLedgerVerificationRunner.base();seed.getJSONObject("settings").put("pageOcr",true).put("customRules",new JSONArray().put(adaptive));isolatedStore.save(seed.toString());PageCaptureService.syncSettings(context,seed.toString());
            JSONObject payment=evaluate(adaptive,movedMini,System.currentTimeMillis(),"adaptive-worker");isolatedStore.enqueue(payment);
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,done::countDown);
            check(done.await(40,TimeUnit.SECONDS),"adaptive background processing completes");
            JSONObject saved=new JSONObject(isolatedStore.load());check(saved.getJSONArray("candidates").length()==1&&saved.getJSONArray("candidates").getJSONObject(0).optBoolean("autoRecorded"),"adaptive result reaches actual automatic-payment reducer");
            movedMini.getJSONArray("lines").put(line("实付金额 ¥25.00",.65));JSONObject review=CustomPaymentRule.reviewCandidate(adaptive,movedMini,System.currentTimeMillis(),"uncertain-worker",java.time.ZoneId.of("Asia/Shanghai"));
            isolatedStore.enqueue(review);java.util.concurrent.CountDownLatch reviewed=new java.util.concurrent.CountDownLatch(1);AutoLedgerProcessor.runIsolated(context,reviewed::countDown);
            check(reviewed.await(40,TimeUnit.SECONDS),"uncertain adaptive processing completes");
            JSONArray savedCandidates=new JSONObject(isolatedStore.load()).getJSONArray("candidates");
            check(savedCandidates.length()==2&&savedCandidates.getJSONObject(1).isNull("amount")&&!savedCandidates.getJSONObject(1).optBoolean("autoRecorded"),"uncertain evidence remains pending without zero-valued bill or hold");
        }finally{getTargetContext().deleteDatabase("custom-rule-test-yubai.db");CustomRuleRegistry.resetProofs(context);}
        if(args.containsKey("sampleDoc")){
            JSONObject sample=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args.getString("sampleDoc"))),java.nio.charset.StandardCharsets.UTF_8));
            JSONObject sampleRule=rule().put("aspect",sample.getDouble("width")/sample.getDouble("height")).put("fields",sample.getJSONObject("fields"));
            JSONObject actual=evaluate(sampleRule,sample,System.currentTimeMillis(),"private-local-replay");
            check(actual.getLong("amount")==Long.parseLong(args.getString("expected")),"actual marked OCR document preserves expected fen");
            check(!actual.getString("reference").isEmpty()&&actual.getString("timeBasis").equals("page-local-time-book-zone"),"actual bill retains reference and transaction time");
            JSONObject learned=PaymentFieldLocator.learn(new JSONObject(sampleRule.toString()).put("adaptive",true),sample);
            check(evaluate(learned,sample,System.currentTimeMillis(),"adaptive-private-replay").getLong("amount")==actual.getLong("amount"),"actual corrected fields learn semantic relationships");
            output.putString("sampleResult",new JSONObject().put("amount",actual.getLong("amount")).put("referencePresent",true).put("timeBasis",actual.getString("timeBasis")).put("ledgerWritten",false).toString());
        }
        if(args.containsKey("image")){
            long expected=Long.parseLong(args.getString("expected","100"));String expectedText=java.math.BigDecimal.valueOf(expected,2).toPlainString();
            var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(args.getString("image"));
            try{
                JSONObject actual=RuleOcrDocument.from(Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS),bitmap.getWidth(),bitmap.getHeight());
                JSONObject imageRule=rule().put("aspect",(double)bitmap.getWidth()/bitmap.getHeight());JSONObject fields=new JSONObject();
                JSONArray lines=actual.getJSONArray("lines");
                String all=lines.toString();Bundle diagnostic=new Bundle();
                JSONObject flags=new JSONObject();for(String label:new String[]{"当前状态","支付方式","转账时间","转帐时间","转账单号","转帐单号","交易单号","交易时间"})flags.put(label,all.contains(label));
                diagnostic.putString("stream","OCR field presence "+flags+"\n");sendStatus(0,diagnostic);
                for(int i=0;i<lines.length();i++){JSONObject line=lines.getJSONObject(i);String value=line.getString("text").replaceAll("\\s+","");
                    if(value.matches("[¥￥*−–-]?"+java.util.regex.Pattern.quote(expectedText)))fields.put("amount",rect(line.getDouble("x"),line.getDouble("y"),line.getDouble("w"),line.getDouble("h")));
                    if(value.matches("(?:当前状态[:：]?)?支付[成戌]功"))fields.put("success",rect(line.getDouble("x"),line.getDouble("y"),line.getDouble("w"),line.getDouble("h")));
                }
                imageRule.put("fields",fields);JSONObject actualCandidate=evaluate(imageRule,actual,now,"actual-photo");
                check(actualCandidate.getLong("amount")==expected,"real user image recognizes expected integer fen");
                if(args.getString("kind","").equals("bill"))check(!actualCandidate.getString("reference").isEmpty()&&actualCandidate.getString("timeBasis").equals("page-local-time-book-zone"),"real bill preserves transaction identifier and original date");
                if(args.containsKey("expectedReferenceLength"))check(actualCandidate.getString("reference").replaceFirst("^wechat:","").length()==Integer.parseInt(args.getString("expectedReferenceLength")),"real wrapped identifier preserves every digit");
                output.putString("imageResult",new JSONObject().put("amount",actualCandidate.getLong("amount")).put("referencePresent",!actualCandidate.getString("reference").isEmpty()).put("timeBasis",actualCandidate.getString("timeBasis")).put("privateTextStored",false).toString());
                RuleImageSession imageSession=new RuleImageSession();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
                try{
                    var loaded=new java.util.concurrent.CountDownLatch(1);var imageError=new java.util.concurrent.atomic.AtomicReference<String>();
                    imageSession.load(context,android.net.Uri.fromFile(new java.io.File(args.getString("image"))),"image-session-test",executor,(type,payload)->{if(!type.equals("rule-image"))imageError.set(payload.optString("message"));loaded.countDown();});
                    check(loaded.await(40,TimeUnit.SECONDS)&&imageError.get()==null,"production image session decodes selected URI and performs local OCR");
                    check(imageSession.test(context,"image-session-test",imageRule).getJSONObject("candidate").getLong("amount")==expected,"production screenshot preview returns correct fen without ledger ingestion");
                    imageSession.clear();rejects(()->imageSession.test(context,"image-session-test",imageRule),"released sample cannot be reused");
                }finally{imageSession.clear();executor.shutdown();}
            }finally{bitmap.recycle();recognizer.close();}
        }
        output.putString("stream","PASS custom rule checks="+checks+" "+output.getString("imageResult","")+"\n");finish(-1,output);
    }catch(Throwable e){output.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(1,output);}}
}
