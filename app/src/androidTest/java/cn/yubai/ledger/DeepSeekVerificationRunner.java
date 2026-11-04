package cn.yubai.ledger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Emulator only, no ledger calls and no network: transport responses below are explicit fixtures. */
public final class DeepSeekVerificationRunner extends Instrumentation {
    private int checks;private Bundle args;
    private interface Run {void run()throws Exception;}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void rejects(Run action,String message)throws Exception {boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,message);}
    private static JSONObject doc(String... lines)throws Exception {JSONArray a=new JSONArray();for(int i=0;i<lines.length;i++)a.put(new JSONObject().put("text",lines[i]).put("x",.1).put("y",.15+i*.06).put("w",.8).put("h",.03));return new JSONObject().put("width",1000).put("height",2000).put("lines",a);}
    private static JSONObject value()throws Exception {return new JSONObject("{\"currency\":\"CNY\",\"amount_fen\":520,\"amount_evidence\":\"实付 5.20 元\",\"direction\":\"out\",\"status\":\"success\",\"status_evidence\":\"支付成功\",\"kind\":\"purchase\",\"merchant\":\"测试餐厅\"}");}
    private static JSONObject envelope(String value,String finish)throws Exception {return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finish).put("message",new JSONObject().put("content",value))));}
    private JSONObject candidate(JSONObject answer)throws Exception {return DeepSeekReceipt.candidate(answer,doc("支付成功","实付 5.20 元"),"wechat","a".repeat(64),1793678400000L,ZoneId.of("Asia/Shanghai"));}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"dedicated emulator only");
        Context isolated=new ContextWrapper(getTargetContext()){@Override public Context getApplicationContext(){return this;} @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("deepseek-test-"+name,mode);}};
        DeepSeekSettings.prefs(isolated).edit().clear().commit();
        try{
            check(!DeepSeekSettings.enabled(isolated),"cloud defaults off");
            rejects(()->DeepSeekSettings.configure(isolated,true,"fixture-not-a-real-api-key",false,false),"explicit upload consent required");
            DeepSeekSettings.configure(isolated,false,"fixture-not-a-real-api-key",false,false);
            check(DeepSeekSettings.secret(isolated).equals("fixture-not-a-real-api-key"),"keystore round trip");
            check(!DeepSeekSettings.prefs(isolated).getString("secret","").contains("fixture"),"credential ciphertext only");
            check(!DeepSeekSettings.status(isolated).toString().contains("fixture"),"status never returns credential");
            DeepSeekSettings.configure(isolated,true,"",false,true);check(DeepSeekSettings.enabled(isolated),"explicit opt-in");
            long revision=DeepSeekSettings.revision(isolated);DeepSeekSettings.configure(isolated,false,"",true,false);
            check(!DeepSeekSettings.enabled(isolated)&&!DeepSeekSettings.status(isolated).getBoolean("configured")&&DeepSeekSettings.revision(isolated)>revision,"forget invalidates in-flight results");
        }finally{DeepSeekSettings.prefs(isolated).edit().clear().commit();}
        check(!DeepSeekFallback.shouldCall(true,false,true,true),"local success bypasses cloud");
        check(!DeepSeekFallback.shouldCall(false,true,true,true),"disabled bypasses cloud");
        check(!DeepSeekFallback.shouldCall(true,true,true,false),"stale image bypasses cloud");
        check(!DeepSeekReceipt.safeImage(doc("聊天信息","支付成功","¥5.20"),true),"chat never uploaded");
        check(!DeepSeekReceipt.safeImage(doc("支付成功","输入密码"),false),"password never uploaded");
        check(!DeepSeekReceipt.safeImage(doc(),true)&&DeepSeekReceipt.safeImage(doc(),false),"only explicit selection permits blank local OCR");
        check(!DeepSeekReceipt.safeImage(doc("支付失败","实付金额5.20"),true),"failed page excluded");
        JSONObject answer=value(),c=candidate(answer);
        JSONObject minimal=new JSONObject("{\"amount_fen\":520,\"direction\":\"out\",\"status\":\"success\",\"kind\":\"purchase\"}");
        check(candidate(minimal).getBoolean("successful"),"four payment fields suffice without currency, merchant or evidence strings");
        check(candidate(new JSONObject(minimal.toString()).put("type","extra-label").put("explanation",new JSONObject())).getBoolean("successful"),"extra fields do not reject useful payment fields");
        check(c.getLong("amount")==520&&c.getString("source").equals("ocr")&&!c.getBoolean("reliable")&&!c.has("autoRecorded")&&!c.has("customRuleHash"),"AI cannot impersonate automatic payment proof");
        check(c.getString("fundsStatus").equals("unknown")&&c.getString("status").equals("pending"),"review-only candidate");
        check(candidate(answer).getString("fingerprint").equals(c.getString("fingerprint")),"retry identity stable");
        for(Object bad:new Object[]{0,-1,5.2,"5.2",true,"NaN",1_000_000_000_001L})check(candidate(value().put("amount_fen",bad)).isNull("amount"),"invalid amount stays unknown, never zero or rounded");
        check(candidate(value().put("amount_fen","520")).getLong("amount")==520,"exact numeric string fen accepted");
        check(candidate(value().put("amount_fen",520.0)).getLong("amount")==520,"whole numeric fen accepted");
        check(candidate(value().put("amount_evidence","原价6.00 实付5.20")).getLong("amount")==520,"unused evidence text is not a required schema field");
        JSONObject injected=candidate(value().put("autoRecorded",true).put("reliable",true).put("source","notification").put("reference","invented"));
        check(!injected.has("autoRecorded")&&!injected.getBoolean("reliable")&&injected.getString("source").equals("ocr")&&injected.getString("reference").isEmpty(),"extra model fields cannot grant authority or invent transaction identity");
        check(candidate(value().put("currency","USD")).isNull("amount"),"explicit other currency not coerced to RMB");
        for(Object merchant:new Object[]{JSONObject.NULL,123,new JSONObject(),"", "x".repeat(121),"bad\nname","unknown","未知"})check(candidate(value().put("merchant",merchant)).getString("merchant").isEmpty(),"unreadable optional merchant becomes empty");
        check(candidate(minimal).getString("merchant").isEmpty()&&candidate(minimal).getString("paymentEvidenceKey").isEmpty(),"no merchant means no amount-only cross-source evidence key");
        for(String missing:new String[]{"amount_fen","direction","status","kind"}){
            JSONObject partial=new JSONObject(minimal.toString());partial.remove(missing);
            check(!candidate(partial).getBoolean("successful"),"missing core field remains review-only");
        }
        JSONObject onlyType=candidate(new JSONObject().put("type","purchase"));
        check(onlyType.isNull("amount")&&!onlyType.getBoolean("successful")&&onlyType.getString("direction").equals("unknown"),"type-only response never manufactures money or payment status");
        check(!candidate(value().put("direction",new JSONObject())).getBoolean("successful"),"invalid direction becomes unknown");
        for(String status:new String[]{"failed","pending","unknown"})check(!candidate(value().put("status",status)).getBoolean("successful"),"non-success status remains unconfirmed");
        for(String kind:new String[]{"refund","internal","repayment","unknown"})check(!candidate(value().put("kind",kind)).getBoolean("successful"),"complex money movement not consumption");
        check(candidate(value().put("amount_fen",JSONObject.NULL)).isNull("amount"),"unknown amount remains null, not zero");
        check(!candidate(value().put("direction","in")).getBoolean("successful"),"incoming not outgoing");
        check(DeepSeekFallback.answer(envelope(answer.toString(),"stop")).getLong("amount_fen")==520,"strict JSON response accepted");
        for(String bad:new String[]{"","   ","```json\n{}\n```","{} {}"})rejects(()->DeepSeekFallback.answer(envelope(bad,"stop")),"empty or malformed response rejected");
        rejects(()->DeepSeekFallback.answer(envelope(answer.toString(),"length")),"truncated response rejected");
        JSONObject fromTransport=DeepSeekFallback.evaluate((secret,body)->{
            check(body.getString("model").equals("deepseek-flash")&&body.getJSONObject("thinking").getString("type").equals("disabled"),"vision model, thinking disabled");
            check(body.getJSONObject("response_format").getString("type").equals("json_object")&&!body.getBoolean("stream"),"bounded JSON transport");
            check(body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"),"vision payload contract");
            return envelope(answer.toString(),"stop");
        },"fixture-not-sent",new byte[]{1,2,3},doc("支付成功","实付5.20元"),"wechat","b".repeat(64),1793678400000L,ZoneId.of("Asia/Shanghai"));
        check(fromTransport.getLong("amount")==520,"mock response travels through production validator");
        DeepSeekSettings.configure(isolated,true,"fixture-not-a-real-api-key",false,true);
        try{
            var calls=new java.util.concurrent.atomic.AtomicInteger();var reply=new java.util.concurrent.atomic.AtomicReference<JSONObject>();
            var completed=new java.util.concurrent.CountDownLatch(1);var failed=new java.util.concurrent.atomic.AtomicReference<String>();
            DeepSeekFallback.Transport fixture=(secret,body)->{calls.incrementAndGet();return envelope(value().toString(),"stop");};
            boolean started=DeepSeekFallback.request(isolated,new byte[]{1,2,3},doc("支付成功","实付5.20元"),"wechat","d".repeat(64),false,true,()->true,received->{reply.set(received);completed.countDown();},m->{failed.set(m);completed.countDown();},fixture);
            check(!started&&calls.get()==0,"production coordinator skips networking on local success");
            started=DeepSeekFallback.request(isolated,new byte[]{1,2,3},doc("支付成功","实付5.20元"),"wechat","d".repeat(64),true,true,()->true,received->{reply.set(received);completed.countDown();},m->{failed.set(m);completed.countDown();},fixture);
            check(started&&completed.await(5,TimeUnit.SECONDS)&&failed.get()==null&&reply.get().getLong("amount")==520,"asynchronous production fallback uses injected transport");
            var limited=new java.util.concurrent.CountDownLatch(1);
            DeepSeekFallback.request(isolated,new byte[]{1,2,3},doc("支付成功","实付5.20元"),"wechat","d".repeat(64),true,true,()->true,received->{},m->limited.countDown(),fixture);
            check(limited.await(2,TimeUnit.SECONDS)&&calls.get()==1,"immediate repeated request is limited, no second API call");
            DeepSeekSettings.prefs(isolated).edit().putLong("attemptAt",0).commit();
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            var visible=new java.util.concurrent.atomic.AtomicBoolean(true);var delivered=new java.util.concurrent.atomic.AtomicInteger();
            DeepSeekFallback.request(isolated,new byte[]{1,2,3},doc("支付成功","实付5.20元"),"wechat","e".repeat(64),true,true,visible::get,
                received->delivered.incrementAndGet(),m->delivered.incrementAndGet(),(secret,body)->{entered.countDown();release.await(2,TimeUnit.SECONDS);return envelope(value().toString(),"stop");});
            check(entered.await(2,TimeUnit.SECONDS),"stale-window fixture reached transport");
            visible.set(false);release.countDown();
            long deadline=SystemClock.elapsedRealtime()+2000;
            while(!DeepSeekSettings.prefs(isolated).getString("state","").equals("discarded")&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
            check(delivered.get()==0&&DeepSeekSettings.prefs(isolated).getString("state","").equals("discarded"),"leaving the window discards late cloud response before any ingestion");

        }finally{DeepSeekSettings.configure(isolated,false,"",true,false);DeepSeekSettings.prefs(isolated).edit().clear().commit();}
        JSONObject live=WindowAiPayment.qualify(candidate(value()),doc("支付成功","实付5.20元","完成"),true);
        check(live.optString("captureMethod").equals("accessibility-window-ai")&&live.optString("source").equals("accessibility")&&live.optString("fundsStatus").equals("paid_out"),"current window can promote clear outgoing payment");
        check(live.optString("timeBasis").equals("page-observed-time")&&!live.has("autoRecorded"),"native proof still delegates automatic eligibility and debit to shared reducer");
        check(WindowAiPayment.qualify(candidate(minimal),doc("支付成功","5.20","完成"),true).optString("captureMethod").equals("accessibility-window-ai"),"empty merchant does not block a clear payment");
        check(WindowAiPayment.qualify(candidate(value()),doc("账单详情","支付成功"),true).optString("source").equals("ocr"),"bill without transaction time never masquerades as today's payment");
        check(WindowAiPayment.qualify(candidate(value()),doc("支付成功","5.20"),false).optString("source").equals("ocr"),"unknown/detail window cannot assume observation time");
        JSONObject datedDoc=doc("账单详情","支付成功","支付时间：2026-11-01 12:00:00");
        JSONObject dated=DeepSeekReceipt.candidate(value(),datedDoc,"alipay","e".repeat(64),1793678400000L,ZoneId.of("Asia/Shanghai"));
        dated=WindowAiPayment.qualify(dated,datedDoc,false);
        check(dated.optString("source").equals("accessibility")&&dated.optString("occurredAt").equals("2026-11-01T04:00:00Z"),"dated detail retains original date and can use shared dedup");
        for(String unsafe:new String[]{"支付失败","处理中","待支付","收款成功","退款成功","充值成功","提现成功","还款成功","输入密码","聊天信息"})
            check(WindowAiPayment.qualify(candidate(value()),doc("支付成功",unsafe),true).optString("source").equals("ocr"),"native negative context overrides cloud success");
        for(JSONObject rejected:new JSONObject[]{candidate(value().put("status","pending")),candidate(value().put("direction","in")),candidate(value().put("kind","refund")),candidate(value().put("amount_fen",JSONObject.NULL))})
            check(WindowAiPayment.qualify(rejected,doc("支付成功"),true).optString("source").equals("ocr"),"uncertain cloud result never gains auto-payment authority");
        JSONObject unordered=doc("支付成功","2026-11-01 12:00:00","其他字段","支付时间");
        unordered.getJSONArray("lines").getJSONObject(1).put("y",.32);
        unordered.getJSONArray("lines").getJSONObject(2).put("y",.5);
        unordered.getJSONArray("lines").getJSONObject(3).put("y",.30);
        JSONObject orderedCandidate=DeepSeekReceipt.candidate(value(),unordered,"wechat","f".repeat(64),1793678400000L,ZoneId.of("Asia/Shanghai"));
        check(orderedCandidate.optString("occurredAt").equals("2026-11-01T04:00:00Z")&&orderedCandidate.optString("timeBasis").equals("page-local-time-book-zone"),"OCR block order cannot detach native bill date from label");
        check(PageWindowOcr.maxEdge()==2880,"single local pass starts at high resolution");
        JSONObject report=new JSONObject().put("checks",checks).put("networkCalls",0).put("ledgerWrites",0).put("transport","mock").put("realDeepSeekTested",false);
        if(args.containsKey("image")){
            android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(args.getString("image"));
            if(bitmap==null)throw new IllegalArgumentException("sample image unavailable");
            var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try{
                long start=SystemClock.elapsedRealtime();var read=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),45,TimeUnit.SECONDS);
                JSONObject document=RuleOcrDocument.from(read,bitmap.getWidth(),bitmap.getHeight());
                JSONObject local=DeepSeekReceipt.local(document,args.getString("origin","wechat"),System.currentTimeMillis(),ZoneId.of("Asia/Shanghai"));
                report.put("sample",new JSONObject().put("width",bitmap.getWidth()).put("height",bitmap.getHeight()).put("localMs",SystemClock.elapsedRealtime()-start)
                    .put("localAccepted",local!=null).put("amountFen",local==null?JSONObject.NULL:local.opt("amount")).put("direction",local==null?"unknown":local.optString("direction"))
                    .put("referencePresent",local!=null&&!local.optString("reference").isEmpty()).put("cloudNeeded",local==null).put("ocrLines",document.getJSONArray("lines").length()));
                if(args.getString("cloud","false").equals("true")){
                    // Explicit comparison mode only. Never enabled by merely finding a key.
                    java.io.File keyFile=new java.io.File(getTargetContext().getFilesDir(),"deepseek-benchmark-key");
                    String key=new String(Files.readAllBytes(keyFile.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
                    if(!key.matches("[A-Za-z0-9_-]{16,256}"))throw new IllegalArgumentException("comparison credential missing");
                    long cloudStart=SystemClock.elapsedRealtime();
                    JSONObject cloud=DeepSeekFallback.evaluate(DeepSeekFallback::http,key,DeepSeekFallback.image(bitmap),document,args.getString("origin","wechat"),"c".repeat(64),System.currentTimeMillis(),ZoneId.of("Asia/Shanghai"));
                    report.put("realDeepSeekTested",true).put("networkCalls",1).put("transport","actual DeepSeek API")
                        .put("cloud",new JSONObject().put("amountFen",cloud.opt("amount")).put("direction",cloud.optString("direction")).put("successful",cloud.optBoolean("successful"))
                            .put("referencePresent",!cloud.optString("reference").isEmpty()).put("elapsedMs",SystemClock.elapsedRealtime()-cloudStart).put("ledgerWrites",0));
                }
            }finally{bitmap.recycle();recognizer.close();}
        }
        Files.write(new java.io.File(getTargetContext().getFilesDir(),"deepseek-test-report.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.putString("stream","PASS DeepSeek checks="+checks+"; mock network; no ledger writes\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(1,result);}}
}
