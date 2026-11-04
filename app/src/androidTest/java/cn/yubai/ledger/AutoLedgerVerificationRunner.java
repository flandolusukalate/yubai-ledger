package cn.yubai.ledger;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.ContextWrapper;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.time.ZoneId;

/** Background processing and cross-instance storage checks, isolated from the user's ledger. */
public final class AutoLedgerVerificationRunner extends Instrumentation {
    private int checks;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static JSONObject base() throws Exception {
        String month=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().substring(0,7);
        JSONObject s=new JSONObject("{\"version\":1,\"revision\":0,\"settings\":{\"timezone\":\"Asia/Shanghai\",\"autoBook\":true,\"pageCapture\":true,\"sources\":[\"com.tencent.mm\"],\"theme\":\"paper\"},\"accounts\":[{\"id\":\"cash\",\"name\":\"日常账户\",\"type\":\"wallet\",\"opening\":0,\"openingDay\":\"1970-01-01\"},{\"id\":\"savings\",\"name\":\"储蓄账户\",\"type\":\"savings\",\"opening\":0,\"openingDay\":\"1970-01-01\"}],\"plans\":{},\"transactions\":[],\"candidates\":[],\"activities\":[],\"adjustments\":[],\"closures\":{},\"audit\":[]}");
        s.getJSONObject("settings").put("autoRecordPayments",true);
        JSONObject plan=new JSONObject().put("month",month).put("income",300000).put("savings",60000).put("emergency",15000).put("startDay",1);
        JSONArray budgets=new JSONArray();String[] ids={"food","transport","fun","goods","trip","fixed"};int[] allocations={105000,15000,30000,15000,30000,30000};
        for(int i=0;i<ids.length;i++)budgets.put(new JSONObject().put("id",ids[i]).put("name",ids[i]).put("allocated",allocations[i]).put("daily",i<4).put("floor",0));
        plan.put("budgets",budgets);s.getJSONObject("plans").put(month,plan);return s;
    }
    private static String ids(Store store) throws Exception {JSONArray rows=new JSONArray(store.inbox()),ids=new JSONArray();for(int i=0;i<rows.length();i++)ids.put(rows.getJSONObject(i).getLong("queueId"));return ids.toString();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Store store=null,other=null;try{
        Context isolated=new ContextWrapper(getTargetContext()){
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath("auto-verification-"+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("auto-verification-"+name,mode,factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return super.openOrCreateDatabase("auto-verification-"+name,mode,factory,handler);}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("auto-verification-"+name,mode);}
        };
        getTargetContext().deleteDatabase("auto-verification-yubai.db");store=new Store(isolated);other=new Store(isolated);
        JSONObject seed=base();store.save(seed.toString());
        JSONObject candidate=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","5.20","完成"),System.currentTimeMillis(),"worker-proof");
        check(candidate!=null&&candidate.getLong("amount")==520,"real native page parser supplies candidate");
        store.enqueue(candidate);String firstIds=ids(store);JSONObject next=new JSONObject(seed.toString()).put("revision",1);
        check(store.commit(0,next.toString(),firstIds),"fresh CAS commits snapshot");
        check(new JSONArray(store.inbox()).length()==0,"snapshot and queue ack commit atomically");
        store.enqueue(candidate);String retryIds=ids(store);String current=store.load();
        check(!other.commit(0,next.toString(),retryIds),"a second Store instance rejects stale revision");
        check(store.load().equals(current)&&new JSONArray(store.inbox()).length()==1,"stale writer cannot overwrite or acknowledge queue");
        boolean invalidRejected=false;try{store.commit(1,new JSONObject(next.toString()).put("revision",2).put("version",999).toString(),retryIds);}catch(Exception expected){invalidRejected=true;}
        check(invalidRejected&&store.load().equals(current)&&new JSONArray(store.inbox()).length()==1,"invalid snapshot rolls back ledger and queue together");
        check(!store.replaceCAS(0,seed.toString()),"restore preview revision cannot replace newer ledger");
        check(store.replaceCAS(1,seed.toString()),"current restore succeeds");
        check(new JSONObject(store.load()).getLong("revision")==2,"restore advances live revision instead of restoring old revision");
        check(store.recovery().equals(current),"restore preserves the actual prior snapshot");
        check(!other.commit(1,new JSONObject(seed.toString()).put("revision",2).toString(),retryIds),"stale UI draft cannot overwrite restored data");
        check(new JSONArray(store.inbox()).length()==1,"restore retains unprocessed captures");
        String damaged="{bad ledger";store.getWritableDatabase().execSQL("UPDATE ledger SET body=? WHERE id=1",new Object[]{damaged});
        check(!store.replaceCorrupt("different damaged bytes",seed.toString())&&store.load().equals(damaged),"damaged snapshot restore compares exact preview bytes");
        check(store.replaceCorrupt(damaged,seed.toString()),"damaged snapshot can be restored without blind overwrite");
        check(store.recovery().equals(damaged)&&new JSONObject(store.load()).getLong("revision")>2,"damaged recovery retained and new revision allocated");
        String noRevision="{\"version\":1}";store.getWritableDatabase().execSQL("UPDATE ledger SET body=? WHERE id=1",new Object[]{noRevision});
        boolean missingRejected=false;try{store.commit(0,next.toString(),retryIds);}catch(Exception expected){missingRejected=true;}
        check(missingRejected,"missing revision is not silently treated as zero");
        check(store.replaceCorrupt(noRevision,seed.toString()),"valid JSON with unusable revision uses exact-byte restore");
        store.save(seed.toString());
        CountDownLatch raceStart=new CountDownLatch(1),raceDone=new CountDownLatch(2);java.util.concurrent.atomic.AtomicInteger winners=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> raceError=new java.util.concurrent.atomic.AtomicReference<>();
        final Store firstWriter=store,secondWriter=other;
        for(Store writer:new Store[]{firstWriter,secondWriter})new Thread(()->{try{raceStart.await();if(writer.commit(0,next.toString(),"[]"))winners.incrementAndGet();}catch(Throwable error){raceError.set(error);}finally{raceDone.countDown();}},"cas-verification").start();
        raceStart.countDown();check(raceDone.await(10,TimeUnit.SECONDS)&&raceError.get()==null&&winners.get()==1,"simultaneous writers have exactly one CAS winner");
        store.save(seed.toString());store.acknowledge(ids(store));store.enqueue(candidate);
        CountDownLatch processed=new CountDownLatch(1);AutoLedgerProcessor.runIsolated(isolated,processed::countDown);
        check(processed.await(45,TimeUnit.SECONDS),"background worker finishes without opening an Activity");
        JSONObject saved=new JSONObject(store.load());
        check(saved.getLong("revision")==1&&new JSONArray(store.inbox()).length()==0,"worker persists reducer output and acknowledges queue atomically");
        check(saved.getJSONArray("candidates").length()==1&&saved.getJSONArray("candidates").getJSONObject(0).getLong("amount")==520,"worker records captured payment amount");
        check(saved.getJSONArray("candidates").getJSONObject(0).optBoolean("autoRecorded"),"explicit payment is automatically held for purpose entry");
        check(saved.getJSONArray("transactions").length()==0,"unknown transfer purpose does not fabricate a confirmed expense");
        store.enqueue(candidate);CountDownLatch repeated=new CountDownLatch(1);AutoLedgerProcessor.runIsolated(isolated,repeated::countDown);
        check(repeated.await(45,TimeUnit.SECONDS),"repeat processing finishes");
        check(new JSONObject(store.load()).getJSONArray("candidates").length()==1&&new JSONArray(store.inbox()).length()==0,"replayed evidence does not duplicate payment");
        check(isolated.getSharedPreferences("collector",0).getString("processor_state","").equals("updated"),"processor state is observable");
        // Cloud interpretation -> native current-window proof -> durable queue -> actual background reducer.
        seed.getJSONObject("settings").put("pageOcr",true).put("alipayPageCapture",true).put("sources",new JSONArray().put("com.tencent.mm").put("com.eg.android.AlipayGphone"));
        for(String origin:new String[]{"wechat","alipay"}){
            store.save(seed.toString());
            JSONObject document=new JSONObject().put("width",1000).put("height",2000).put("lines",new JSONArray().put(new JSONObject().put("text","支付成功")).put(new JSONObject().put("text","实付20.00元")));
            JSONObject response=new JSONObject().put("amount_fen",2000).put("direction","out").put("status","success").put("kind","purchase");
            JSONObject ai=WindowAiPayment.qualify(DeepSeekReceipt.candidate(response,document,origin,"a".repeat(64),System.currentTimeMillis(),ZoneId.of("Asia/Shanghai")),document,true);
            store.enqueue(ai);CountDownLatch cloudProcessed=new CountDownLatch(1);AutoLedgerProcessor.runIsolated(isolated,cloudProcessed::countDown);
            check(cloudProcessed.await(45,TimeUnit.SECONDS),"AI payment background worker completed");
            JSONObject payment=new JSONObject(store.load()).getJSONArray("candidates").getJSONObject(0);
            check(payment.optBoolean("autoRecorded")&&payment.getLong("amount")==2000&&new JSONArray(store.inbox()).length()==0,"clear live AI payment records automatically via atomic shared reducer");
            store.enqueue(ai);CountDownLatch cloudRepeated=new CountDownLatch(1);AutoLedgerProcessor.runIsolated(isolated,cloudRepeated::countDown);
            check(cloudRepeated.await(45,TimeUnit.SECONDS),"AI replay worker completed");
            check(new JSONObject(store.load()).getJSONArray("candidates").length()==1,"durable cloud retry retains exactly one automatic payment");
        }
        result.putInt("passed",checks);result.putString("status","PASS: isolated CAS, atomic queue, restore and background shared-reducer worker");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("failure",error.toString());result.putInt("passedBeforeFailure",checks);finish(Activity.RESULT_CANCELED,result);}finally{if(other!=null)other.close();if(store!=null)store.close();}}
}
