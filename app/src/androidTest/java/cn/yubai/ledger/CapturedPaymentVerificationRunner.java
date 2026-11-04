package cn.yubai.ledger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Emulator only: captured task policy + delayed transport + actual isolated queue/reducer. */
public final class CapturedPaymentVerificationRunner extends Instrumentation {
    private int checks;
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static final long AT=Instant.parse("2026-11-01T15:59:59Z").toEpochMilli();
    private CapturedPayment frame(Context c,String origin,long epoch)throws Exception{
        return new CapturedPayment(c,epoch,7,"frame-"+epoch,origin.equals("wechat")?"com.tencent.mm":AlipayCaptureWindow.PKG,
            origin.equals("wechat")?"com.tencent.mm.plugin.remittance.ui.RemittanceResultUI":AlipayCaptureWindow.MSP,true,false,null,1,AT);
    }
    private static JSONObject doc()throws Exception{return new JSONObject().put("width",1000).put("height",2000).put("lines",new JSONArray().put(new JSONObject().put("text","支付成功")).put(new JSONObject().put("text","实付5.20元")));}
    private static JSONObject reply()throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content","{\"amount_fen\":520,\"direction\":\"out\",\"status\":\"success\",\"kind\":\"purchase\"}"))));}
    private void process(Context isolated)throws Exception{CountDownLatch done=new CountDownLatch(1);AutoLedgerProcessor.runIsolated(isolated,done::countDown);check(done.await(20,TimeUnit.SECONDS),"background worker completes");}
    private void awaitTransportIdle()throws Exception{
        // Observe only liveness of the injected transport, not its credential or payload.
        var field=DeepSeekFallback.class.getDeclaredField("BUSY");field.setAccessible(true);
        var busy=(java.util.concurrent.atomic.AtomicBoolean)field.get(null);
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(busy.get()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
        check(!busy.get(),"mock cloud coordinator completes");
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Store store=null;Context isolated=null;try{
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"dedicated emulator only");
        isolated=new ContextWrapper(getTargetContext()){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("captured-test-"+name,mode);}
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath("captured-test-"+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("captured-test-"+name,mode,factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return super.openOrCreateDatabase("captured-test-"+name,mode,factory,handler);}
        };
        final Context context=isolated;
        getTargetContext().deleteDatabase("captured-test-yubai.db");store=new Store(context);final Store destination=store;
        var prefs=context.getSharedPreferences("collector",0);prefs.edit().clear().commit();
        JSONObject seed=AutoLedgerVerificationRunner.base();
        // Fixed November fixture so a delayed 23:59:59 capture can be checked independently of today.
        JSONObject plan=seed.getJSONObject("plans").getJSONObject(seed.getJSONObject("plans").keys().next());plan.put("month","2026-11");
        seed.put("plans",new JSONObject().put("2026-11",plan));
        seed.getJSONObject("settings").put("pageOcr",true).put("alipayPageCapture",true).put("sources",new JSONArray().put("com.tencent.mm").put(AlipayCaptureWindow.PKG));
        PageCaptureService.syncSettings(context,seed.toString());
        CapturedPayment local=frame(context,"wechat",1);
        PageCaptureService.syncSettings(context,seed.toString());
        check(local.active(context,1,true),"ordinary resync does not cancel captured frame");
        prefs.edit().putString("page_source_package",AlipayCaptureWindow.PKG).putString("page_window_class",AlipayCaptureWindow.BILL).putString("ledger_timezone","America/New_York").commit();
        JSONObject localResult=local.parseBuiltIn(doc(),"支付成功\n待测试收款人确认收款\n5.20\n完成");
        check(local.active(context,1,true)&&localResult!=null,"local recognition survives simulated switch to another application");
        check(localResult.getString("origin").equals("wechat")&&localResult.getString("occurredAt").equals(Instant.ofEpochMilli(AT).toString()),"source and time come from captured frame");
        check(local.zone.getId().equals("Asia/Shanghai"),"capture timezone is frozen");
        JSONArray detached=local.rules();detached.put(new JSONObject().put("id","changed"));check(local.rules().length()==0,"caller cannot mutate captured rules");
        check(!local.active(context,2,true)&&!local.active(context,1,false),"service interrupt or destruction cancels task");
        JSONObject disabled=new JSONObject(seed.toString());disabled.getJSONObject("settings").put("pageOcr",false);
        PageCaptureService.syncSettings(context,disabled.toString());check(!local.active(context,1,true),"turning OCR off cancels captured task");
        PageCaptureService.syncSettings(context,seed.toString());check(!local.active(context,1,true),"off then on cannot revive an old capture");
        CapturedPayment repeated=frame(context,"wechat",2);AtomicInteger saves=new AtomicInteger();
        check(repeated.deliver(saves::incrementAndGet)&&!repeated.deliver(saves::incrementAndGet)&&saves.get()==1,"duplicate callback delivers once");
        check(!repeated.active(context,1,true),"delivered task is closed");
        check(repeated.authorized(context,1,true),"delayed capture prompt remains eligible after fast OCR delivery");
        check(!repeated.authorized(context,2,true)&&!repeated.authorized(context,1,false),"late prompt respects service shutdown and interruption");
        PageCaptureService.syncSettings(context,disabled.toString());
        check(!repeated.authorized(context,1,true),"late prompt respects revoked OCR permission");
        PageCaptureService.syncSettings(context,seed.toString());
        check(!repeated.authorized(context,1,true),"re-enabling does not revive a stale prompt");
        CapturedPayment retry=frame(context,"wechat",3);
        try{retry.deliver(()->{throw new java.io.IOException("fixture failure");});}catch(java.io.IOException expected){}
        check(retry.deliver(saves::incrementAndGet),"failed store write does not falsely acknowledge capture");
        DeepSeekSettings.prefs(context).edit().clear().commit();DeepSeekSettings.configure(context,true,"fixture-not-a-real-key",false,true);
        JSONArray cases=new JSONArray();
        for(String origin:new String[]{"wechat","alipay"}){
            store.save(seed.toString());PageCaptureService.syncSettings(context,seed.toString());
            CapturedPayment captured=frame(context,origin,10);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
            AtomicReference<JSONObject> accepted=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
            DeepSeekSettings.prefs(context).edit().putLong("attemptAt",0).commit();
            boolean started=DeepSeekFallback.requestCaptured(context,new byte[]{1},doc(),captured,"a".repeat(64),()->captured.active(context,1,true),c->{
                try{JSONObject paid=WindowAiPayment.qualify(c,doc(),captured.result);accepted.set(paid);captured.deliver(()->destination.enqueue(paid));}catch(Exception e){error.set(e.toString());}finally{done.countDown();}
            },e->{error.set(e);done.countDown();},(key,body)->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return reply();});
            check(started&&entered.await(2,TimeUnit.SECONDS),"captured image reaches delayed simulated DS");
            prefs.edit().putString("page_source_package",origin.equals("wechat")?AlipayCaptureWindow.PKG:"com.tencent.mm").putString("page_window_class","different-page").commit();
            check(captured.active(context,1,true),"navigation does not revoke captured-image permission");release.countDown();
            check(done.await(3,TimeUnit.SECONDS)&&error.get()==null&&accepted.get()!=null,"DS result arrives after simulated navigation");
            check(accepted.get().getString("origin").equals(origin)&&accepted.get().getString("occurredAt").equals(Instant.ofEpochMilli(AT).toString()),"delayed DS preserves source and pre-midnight capture time");
            check(!captured.deliver(()->destination.enqueue(accepted.get()))&&new JSONArray(store.inbox()).length()==1,"repeated late callback cannot enqueue twice");
            process(context);JSONObject saved=new JSONObject(store.load());
            check(saved.getJSONArray("candidates").length()==1&&saved.getJSONArray("candidates").getJSONObject(0).optBoolean("autoRecorded"),"background reducer automatically records detached payment");
            store.enqueue(accepted.get());process(context);
            check(new JSONObject(store.load()).getJSONArray("candidates").length()==1&&new JSONArray(store.inbox()).length()==0,"queue retry does not duplicate saved payment");
            cases.put(new JSONObject().put("origin",origin).put("state",new JSONObject(store.load())));
            awaitTransportIdle();
        }
        for(String cancel:new String[]{"ocr","deepseek"}){
            PageCaptureService.syncSettings(context,seed.toString());
            DeepSeekSettings.configure(context,true,"fixture-not-a-real-key",false,true);
            DeepSeekSettings.prefs(context).edit().putLong("attemptAt",0).commit();
            CapturedPayment waiting=frame(context,"wechat",20);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger callbacks=new AtomicInteger();
            DeepSeekFallback.requestCaptured(context,new byte[]{1},doc(),waiting,"b".repeat(64),()->waiting.active(context,1,true),
                c->callbacks.incrementAndGet(),e->callbacks.incrementAndGet(),(key,body)->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return reply();});
            check(entered.await(2,TimeUnit.SECONDS),"cancellation fixture waits for DS response");
            if(cancel.equals("ocr"))PageCaptureService.syncSettings(context,disabled.toString());
            else DeepSeekSettings.configure(context,false,"",false,false);
            release.countDown();awaitTransportIdle();
            check(callbacks.get()==0&&new JSONArray(store.inbox()).length()==0,"revocation discards late response without a new record: "+cancel);
        }
        JSONObject report=new JSONObject().put("checks",checks).put("networkCalls",0).put("personalLedgerWrites",0).put("navigation","simulated").put("cases",cases);
        Files.write(new java.io.File(getTargetContext().getFilesDir(),"captured-payment-test.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        result.putString("stream","PASS captured payment checks="+checks+"; mocked transport; isolated SQLite\n");finish(-1,result);
    }catch(Throwable error){result.putString("stream","FAIL after "+checks+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");finish(1,result);}
    finally{if(store!=null)store.close();if(isolated!=null){DeepSeekSettings.prefs(isolated).edit().clear().commit();}}}
}
