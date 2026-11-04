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
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real screenshot, one local OCR pass; failed reads replay retained API fields. No network or ledger. */
public final class WindowAiScreenshotRunner extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(!android.os.Build.FINGERPRINT.contains("generic")&&!android.os.Build.MODEL.contains("sdk"))throw new IllegalStateException("emulator only");
        var dir=new java.io.File(getTargetContext().getFilesDir(),"window-ai-test");
        JSONObject sample=new JSONObject(new String(Files.readAllBytes(new java.io.File(dir,"sample.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        Bitmap original=BitmapFactory.decodeFile(new java.io.File(dir,"sample.png").getPath());
        if(original==null)throw new IllegalArgumentException("missing sample");
        double scale=Math.min(1,(double)PageWindowOcr.maxEdge()/Math.max(original.getWidth(),original.getHeight()));
        Bitmap image=Bitmap.createScaledBitmap(original,(int)(original.getWidth()*scale),(int)(original.getHeight()*scale),true);
        var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try{
            long now=System.currentTimeMillis(),started=SystemClock.elapsedRealtime();
            String origin=sample.getString("origin"),window=sample.getString("window");
            boolean immediate=origin.equals("alipay")?AlipayCaptureWindow.result(window):WechatCaptureWindow.result(window);
            var text=Tasks.await(recognizer.process(InputImage.fromBitmap(image,0)),30,TimeUnit.SECONDS);
            JSONObject doc=RuleOcrDocument.from(text,image.getWidth(),image.getHeight());
            // Same built-in parser calls as PageWindowOcr; custom-rule matching is outside this fixture.
            JSONObject c=origin.equals("alipay")?AlipayPaymentPage.parse(doc,now,"fixture",window,ZoneId.of("Asia/Shanghai")):
                ScreenOcrParser.parse(ScreenOcrText.read(text),now,"fixture",immediate);
            long localMs=SystemClock.elapsedRealtime()-started;boolean local=c!=null;
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            if(!local){
                c=DeepSeekFallback.evaluate((key,body)->{
                    calls.incrementAndGet();
                    return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",sample.getJSONObject("retainedApiFields").toString()))));
                },"not-a-network-key",new byte[]{1},doc,origin,"f".repeat(64),now,ZoneId.of("Asia/Shanghai"));
                c=WindowAiPayment.qualify(c,doc,immediate);
            }
            if(c==null||c.optLong("amount")!=sample.getLong("expectedFen")||!c.optBoolean("successful")||!c.optString("direction").equals("out"))throw new AssertionError("payment fields incorrect");
            if(!local&&!c.optString("captureMethod").equals("accessibility-window-ai"))throw new AssertionError("clear window fallback was not qualified; safe="+DeepSeekReceipt.safeImage(doc,true)+" timeBasis="+c.optString("timeBasis"));
            JSONObject report=new JSONObject().put("sample",sample.getString("name")).put("localPasses",1).put("width",image.getWidth()).put("height",image.getHeight())
                .put("localAccepted",local).put("localMs",localMs).put("replayedApiResponses",calls.get()).put("networkCalls",0).put("ledgerWrites",0)
                .put("amountFen",c.getLong("amount")).put("direction",c.optString("direction")).put("successful",c.optBoolean("successful")).put("timeBasis",c.optString("timeBasis"));
            Files.write(new java.io.File(dir,"report.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            result.putString("stream","PASS "+report+"\n");finish(-1,result);
        }finally{recognizer.close();if(image!=original)image.recycle();original.recycle();}
    }catch(Throwable error){result.putString("stream","FAIL "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");finish(1,result);}}
}
