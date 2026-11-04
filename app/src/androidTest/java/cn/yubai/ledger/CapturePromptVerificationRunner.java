package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;

/** Emulator-only permission check and optional private screenshot replay; never opens a ledger. */
public final class CapturePromptVerificationRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle input){super.onCreate(input);args=input;start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        if(!android.os.Build.FINGERPRINT.contains("generic")&&!android.os.Build.MODEL.contains("sdk"))throw new AssertionError("emulator only");
        var context=getTargetContext();
        var info=context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS);
        if(!java.util.Arrays.asList(info.requestedPermissions).contains(android.Manifest.permission.POST_NOTIFICATIONS))throw new AssertionError("missing notification declaration");
        if(CapturePrompt.enabled(context)!=Boolean.parseBoolean(args.getString("enabled","false")))throw new AssertionError("notification status differs from OS");
        if(args.containsKey("image")){
            var bitmap=android.graphics.BitmapFactory.decodeFile(args.getString("image"));
            var recognizer=com.google.mlkit.vision.text.TextRecognition.getClient(new com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build());
            try{
                var result=com.google.android.gms.tasks.Tasks.await(recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap,0)),30,java.util.concurrent.TimeUnit.SECONDS);
                var doc=RuleOcrDocument.from(result,bitmap.getWidth(),bitmap.getHeight());
                var rule=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args.getString("rule"))),java.nio.charset.StandardCharsets.UTF_8));
                String ruleError="";JSONObject candidate=null;
                try{candidate=CustomPaymentRule.evaluate(rule,doc,System.currentTimeMillis(),"replay",java.time.ZoneId.of("Asia/Shanghai"));}catch(IllegalArgumentException e){ruleError=e.getMessage();}
                JSONObject local=DeepSeekReceipt.local(doc,"wechat",System.currentTimeMillis(),java.time.ZoneId.of("Asia/Shanghai"));
                JSONObject report=new JSONObject().put("ruleError",ruleError).put("rulePassed",candidate!=null).put("localPassed",local!=null).put("cloudSafe",DeepSeekReceipt.safeImage(doc,true));
                if(local!=null)report.put("localAmount",local.getLong("amount")).put("referenceLength",local.optString("reference").replaceFirst("^wechat:","").length());
                out.putString("replay",report.toString());
                // Explicit optional private diagnostic destination; test APK only, no production persistence.
                if(args.containsKey("document"))java.nio.file.Files.write(java.nio.file.Paths.get(args.getString("document")),doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }finally{bitmap.recycle();recognizer.close();}
        }
        out.putString("stream","PASS notification permission state="+CapturePrompt.enabled(context)+"; no ledger writes\n");finish(-1,out);
    }catch(Throwable e){out.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(1,out);}}
}
