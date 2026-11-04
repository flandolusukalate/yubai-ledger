package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public final class ScreenOcrVerificationRunner extends Instrumentation {
    private Bundle args;private int checks;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        String text="18:12\n支付成功\n待测试收款人3.17确认收款\n¥0.01\n完成";
        JSONObject c=ScreenOcrParser.parse(text,1793610000000L,"one");
        check(c!=null&&c.getLong("amount")==1,"amount role, not digits in recipient nickname");
        check(c.getString("source").equals("screen_ocr")&&c.getString("direction").equals("out")&&!c.getBoolean("reliable"),"screen evidence source/direction");
        JSONObject star=ScreenOcrParser.parse(text.replace("¥0.01","*0.01"),0,"star");
        check(star!=null&&star.getLong("amount")==1,"real ML Kit asterisk currency confusion in complete sender result");
        JSONObject title=ScreenOcrParser.parse(text.replace("支付成功","支付戌功"),0,"observed-title");
        check(title!=null&&title.getLong("amount")==1,"observed 成/戌 OCR confusion only in complete sender result");
        for(String invalid:new String[]{"支付失败","支付未成功","未支付成功","支付?功"})check(ScreenOcrParser.parse(text.replace("支付成功",invalid),0,"invalid-title")==null,"failure/unclear title is never normalized to success");
        check(ScreenOcrParser.parse("支付戌功\n余额\n¥0.01\n完成",0,"not-payment")==null,"title correction alone never assigns amount role or outgoing direction");
        check(ScreenOcrParser.parse("余额\n*0.01\n完成",0,"invalid")==null,"asterisk fallback never assigns a balance");
        check(ScreenOcrParser.parse(text+"\n*0.02",0,"ambiguous")==null,"asterisk second amount remains a conflict");
        check(!c.toString().contains("测试收款人"),"recipient and full OCR not retained");
        JSONObject variant=ScreenOcrParser.parse(text.replace("18:12","19:15").replace("¥0.01","¥\n0.01"),1793613600000L,"two");
        check(variant.getString("screenEvidenceKey").equals(c.getString("screenEvidenceKey")),"clock/currency layout do not defeat cross-session suspicion");
        check(!variant.getString("fingerprint").equals(c.getString("fingerprint")),"separate session is evidence, not silently merged");
        for(String bad:new String[]{text.replace("支付成功","支付失败"),text+"\n¥30.00",text+"\n验证码123456",text.replace("待测试收款人3.17确认收款","待你确认收款"),"余额\n¥0.01\n完成"})
            check(ScreenOcrParser.parse(bad,0,"x")==null,"ambiguous/failed/private/incoming context rejected");
        String detail="18:32\n待测试收款人3.17收款\n¥0.01\n1天内对方未收款，将退还给你。提醒对方收款\n转账时间\n2026年11月02日 18:04:48\n账单详情";
        JSONObject pending=ScreenOcrParser.parse(detail,1793617200000L,"detail");
        check(pending!=null&&pending.getLong("amount")==1,"sender pending detail preserves amount");
        check(pending.getString("evidence").equals("wechat-paid-transfer-pending-detail-v1")&&pending.getBoolean("successful")&&pending.getString("recipientStatus").equals("pending"),"sender funding independent of recipient acceptance");
        check(pending.getString("occurredAt").equals("2026-11-02T10:04:48Z"),"Chinese-labeled transfer time is actual occurrence time");
        check(pending.getString("screenEvidenceKey").equals(c.getString("screenEvidenceKey")),"success page and pending detail share duplicate suspicion key");
        check(ScreenOcrParser.parse(detail.replace("账单详情","果单详情"),1793617200000L,"footer")!=null,"observed footer glyph confusion preserves strict sender context");
        for(String bad:new String[]{detail.replace("待测试收款人3.17收款","待你收款"),detail.replace("将退还给你","将退还给对方"),detail.replace("2026年11月02日","2026年02月30日"),detail+"\n余额 ¥30.00",detail+"\n发送",detail+"\n已退回"})
            check(ScreenOcrParser.parse(bad,1793617200000L,"bad-detail")==null,"incoming, invalid date, ambiguity and refund detail excluded");
        check(PageWindowOcr.maxEdge()==2880,"single local frame preserves high-resolution detail");
        if(args.containsKey("image")){
            var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try{
                var image=InputImage.fromFilePath(getTargetContext(),android.net.Uri.fromFile(new java.io.File(args.getString("image"))));
                var recognized=Tasks.await(recognizer.process(image),30,TimeUnit.SECONDS);
                String actual=ScreenOcrText.read(recognized);
                JSONObject parsed=ScreenOcrParser.parse(actual,System.currentTimeMillis(),"real-image");
                check(parsed!=null,"actual screenshot has a supported payment structure");
                check(parsed.getLong("amount")==Long.parseLong(args.getString("expected","1")),"actual screenshot amount");
                android.graphics.Bitmap original=android.graphics.BitmapFactory.decodeFile(args.getString("image"));
                int acceptedAttempt=0;long started=android.os.SystemClock.elapsedRealtime();
                try{
                    for(int attempt=1;attempt<=1;attempt++){
                        double scale=Math.min(1,(double)PageWindowOcr.maxEdge()/Math.max(original.getWidth(),original.getHeight()));
                        android.graphics.Bitmap scaled=android.graphics.Bitmap.createScaledBitmap(original,(int)(original.getWidth()*scale),(int)(original.getHeight()*scale),true);
                        try{
                            JSONObject resized=ScreenOcrParser.parse(ScreenOcrText.read(Tasks.await(recognizer.process(InputImage.fromBitmap(scaled,0)),30,TimeUnit.SECONDS)),System.currentTimeMillis(),"scaled",true);
                            if(resized!=null){
                                check(resized.getLong("amount")==parsed.getLong("amount"),"any accepted production frame must preserve actual amount");
                                check(resized.getString("evidence").equals(parsed.getString("evidence")),"production sequence must preserve actual transfer type, not misclassify as merchant");
                                acceptedAttempt=attempt;break;
                            }
                        }finally{if(scaled!=original)scaled.recycle();}
                    }
                    check(acceptedAttempt>0,"single high-resolution local pass recovers the receipt");
                }finally{original.recycle();}
                result.putString("imageResolution","acceptedAttempt="+acceptedAttempt+"; OCR processing ms="+(android.os.SystemClock.elapsedRealtime()-started));
                result.putString("actualImage",new JSONObject().put("amount",parsed.getLong("amount")).put("source",parsed.getString("source")).put("direction",parsed.optString("direction")).put("recipientStatus",parsed.optString("recipientStatus")).put("rawTextStored",false).toString());
            }finally{recognizer.close();}
        }
        result.putString("stream","PASS screen OCR checks="+checks+" "+result.getString("actualImage","")+" "+result.getString("imageResolution","")+"\n");finish(-1,result);
    }catch(Throwable error){result.putString("stream","FAIL "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");finish(1,result);}}
}
