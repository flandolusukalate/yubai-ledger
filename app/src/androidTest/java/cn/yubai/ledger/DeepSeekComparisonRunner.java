package cn.yubai.ledger;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, bounded screenshot benchmark. No Activity, Store, enqueue or ledger reducer calls. */
public final class DeepSeekComparisonRunner extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    private static String normalize(String s) { return s.replaceAll("\\s+", "").replace('（','(').replace('）',')'); }
    private static Object merchantCorrect(JSONObject candidate, JSONObject expected) throws Exception {
        if(!expected.has("merchant")) return JSONObject.NULL;
        return candidate!=null && normalize(candidate.optString("merchant")).equals(normalize(expected.getString("merchant")));
    }
    private static JSONObject summary(JSONObject c, JSONObject expected) throws Exception {
        return new JSONObject().put("accepted",c!=null).put("amountFen",c==null?JSONObject.NULL:c.opt("amount"))
            .put("direction",c==null?"unknown":c.optString("direction","unknown"))
            .put("successful",c!=null&&c.optBoolean("successful"))
            .put("correctAmount",c!=null&&c.optLong("amount",-1)==expected.getLong("amountFen"))
            .put("correctDirection",c!=null&&c.optString("direction").equals(expected.getString("direction")))
            .put("correctPaidStatus",c!=null&&c.optBoolean("successful")==expected.getBoolean("paid"))
            .put("correctMerchant",merchantCorrect(c,expected))
            .put("merchantEmpty",c==null||c.optString("merchant").isEmpty())
            .put("referencePresent",c!=null&&!c.optString("reference").isEmpty());
    }
    private static void write(File file, JSONObject report) throws Exception {
        Files.write(file.toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(!"true".equals(arguments.getString("confirmNetwork"))) throw new IllegalArgumentException("Explicit comparison network authorization required");
            if(!DeepSeekSettings.enabled(getTargetContext())) throw new IllegalArgumentException("Device DeepSeek configuration is disabled or missing");
            File dir=new File(getTargetContext().getFilesDir(),"deepseek-comparison");
            JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(dir,"manifest.json").toPath()),StandardCharsets.UTF_8));
            JSONArray samples=manifest.getJSONArray("samples"); int rounds=manifest.getInt("rounds");
            if(samples.length()<1||samples.length()>10||rounds<1||rounds>3) throw new IllegalArgumentException("Benchmark request limit");
            String secret=DeepSeekSettings.secret(getTargetContext()); // Stays in the application process.
            JSONArray rows=new JSONArray(); JSONObject report=new JSONObject().put("rows",rows).put("model",DeepSeekFallback.MODEL)
                .put("rounds",rounds).put("ledgerWrites",0).put("startedAt",java.time.Instant.now().toString())
                .put("deviceModel",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT);
            File reportFile=new File(dir,"report.json"); write(reportFile,report);
            var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try {
                for(int round=1;round<=rounds;round++) for(int i=0;i<samples.length();i++) {
                    JSONObject sample=samples.getJSONObject(i); String name=sample.getString("sample"),origin=sample.getString("origin");
                    if(!name.matches("[a-z0-9-]{1,80}")||!java.util.Set.of("wechat","alipay").contains(origin)) throw new IllegalArgumentException("Invalid benchmark sample");
                    Bitmap bitmap=BitmapFactory.decodeFile(new File(dir,name+".png").getPath());
                    if(bitmap==null) throw new IllegalArgumentException("Benchmark image unavailable");
                    JSONObject row=new JSONObject().put("sample",name).put("round",round).put("width",bitmap.getWidth()).put("height",bitmap.getHeight());
                    try {
                        long start=SystemClock.elapsedRealtime();
                        var read=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),45,TimeUnit.SECONDS);
                        JSONObject doc=RuleOcrDocument.from(read,bitmap.getWidth(),bitmap.getHeight());
                        long now=System.currentTimeMillis(); ZoneId zone=ZoneId.of("Asia/Shanghai");
                        JSONObject local=DeepSeekReceipt.local(doc,origin,now,zone);
                        row.put("local",summary(local,sample).put("elapsedMs",SystemClock.elapsedRealtime()-start));
                        if(!DeepSeekReceipt.safeImage(doc,false)) throw new IllegalArgumentException("Sensitive image rejected before upload");
                        long cloudStart=SystemClock.elapsedRealtime(); byte[] jpeg=DeepSeekFallback.image(bitmap);
                        JSONObject diagnostics=new JSONObject();
                        try {
                            JSONObject request=DeepSeekFallback.body(jpeg);
                            diagnostics.put("promptSha256",PaymentListener.digest(request.getJSONArray("messages").getJSONObject(0).getString("content")));
                            JSONObject response=DeepSeekFallback.http(secret,request);
                            long responseAt=SystemClock.elapsedRealtime();
                            JSONObject answer=DeepSeekFallback.answer(response);
                            JSONObject shape=new JSONObject(); var keys=answer.keys();
                            while(keys.hasNext()) {String k=keys.next();Object v=answer.opt(k);shape.put(k.matches("[a-z_]{1,40}")?k:"redacted-key",v==JSONObject.NULL?"null":v.getClass().getSimpleName());}
                            diagnostics.put("shape",shape);
                            JSONObject cloud=DeepSeekReceipt.candidate(answer,doc,origin,sample.getString("sha256"),now,zone);
                            JSONObject cloudResult=summary(cloud,sample).put("elapsedMs",SystemClock.elapsedRealtime()-cloudStart)
                                .put("requestAndImageMs",responseAt-cloudStart)
                                .put("paymentStatus",java.util.Set.of("success","pending","failed","unknown").contains(answer.optString("status"))?answer.optString("status"):"unknown")
                                .put("kind",java.util.Set.of("purchase","transfer","refund","internal","repayment","unknown").contains(answer.optString("kind"))?answer.optString("kind"):"unknown")
                                .put("candidatePending",cloud.getString("status").equals("pending")).put("diagnostics",diagnostics);
                            JSONObject usage=response.optJSONObject("usage");
                            if(usage!=null) cloudResult.put("promptTokens",usage.optLong("prompt_tokens")).put("completionTokens",usage.optLong("completion_tokens"));
                            row.put("cloud",cloudResult);
                        } catch(Exception error) {
                            // Production errors contain no body, key or OCR content. Never log raw API responses.
                            row.put("cloud",new JSONObject().put("accepted",false).put("elapsedMs",SystemClock.elapsedRealtime()-cloudStart)
                                .put("errorType",error.getClass().getSimpleName()).put("error",error instanceof IllegalArgumentException?error.getMessage():"Network or parsing failure")
                                .put("diagnostics",diagnostics));
                        } finally { java.util.Arrays.fill(jpeg,(byte)0); }
                        rows.put(row); write(reportFile,report);
                        Bundle progress=new Bundle(); progress.putString("stream",row.toString()+"\n"); sendStatus(0,progress);
                        // Stop at the first network/schema failure; no automatic repeated billed requests.
                        if(!row.getJSONObject("cloud").optBoolean("accepted")) throw new IllegalStateException("Comparison stopped; sanitized failure in report");
                    } finally { bitmap.recycle(); }
                }
            } finally { recognizer.close(); }
            report.put("completed",true); write(reportFile,report);
            result.putString("stream","PASS screenshot comparison; no ledger calls\n"); finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream","FAIL "+error.getClass().getSimpleName()+": "+(error instanceof IllegalArgumentException||error instanceof IllegalStateException?error.getMessage():"Comparison setup failed")+"\n"); finish(1,result);
        }
    }
}
