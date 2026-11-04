package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Synthetic result-page fixtures and real offline OCR model. No personal ledger writes. */
public final class MerchantOcrVerificationRunner extends Instrumentation {
    private int checks;
    private final long now=1793692800000L;
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;}
    private JSONObject parse(String text) throws Exception{return ScreenOcrParser.parse(text,now,"merchant-test",true);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();java.util.TimeZone previousZone=java.util.TimeZone.getDefault();try{
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        String labelled="支付成功\n实付金额：¥20.00\n商户：学校食堂\n完成";
        JSONObject c=parse(labelled);
        check(c!=null&&c.getLong("amount")==2000&&c.getString("merchant").equals("学校食堂"),"amount and merchant");
        check(c.getString("direction").equals("out")&&c.getString("evidence").equals("wechat-paid-merchant-result-v1"),"outgoing merchant evidence");
        check(!c.has("text")&&!c.has("body"),"raw text not persisted");
        check(parse(labelled.replace("支付成功","支付戌功")).getLong("amount")==2000,"observed title glyph correction also retains merchant amount roles");
        String discount="支付成功\n¥20.00\n实付金额\n¥20.00\n原价\n¥25.00\n优惠金额\n¥5.00\n零钱余额\n¥98.00\n商户名称\n学校食堂\n完成";
        check(parse(discount).getLong("amount")==2000,"actual amount wins over original price, discount and balance");
        check(parse("付款成功\n¥\n20.00\n收款方：学校食堂\n完成").getLong("amount")==2000,"known result supports unlabelled prominent amount and split currency");
        check(ScreenOcrParser.parse("支付成功\n¥20.00\n商户：学校食堂\n完成",now,"untrusted")==null,"unlabelled amount needs actual verified result window");
        check(parse("支付成功\n20.00\n完成").optString("merchant").isEmpty(),"unavailable merchant is not invented");
        check(parse(labelled.replace("20.00","1,234.56")).getLong("amount")==123456,"exact cents and valid thousands separator");
        String id=labelled.replace("完成","交易单号\n42000012345678901234\n商户单号\nSHOP123456\n支付时间\n2026-11-03 12:00:00\n完成");
        JSONObject identified=parse(id);
        check(identified.getString("reference").equals("wechat:42000012345678901234"),"only platform transaction ID used for cross-source identity");
        check(identified.getString("occurredAt").equals("2026-11-03T04:00:00Z")&&identified.getString("timeBasis").equals("page-local-time-device-zone"),"actual time separate from collection");
        for(String bad:new String[]{labelled.replace("支付成功","支付失败"),labelled.replace("支付成功","处理中"),labelled.replace("支付成功","收款成功"),labelled.replace("支付成功","已取消"),labelled.replace("20.00","20.001"),labelled.replace("20.00","1,23.45"),labelled.replace("20.00","0.00"),labelled.replace("20.00","-20.00"),labelled.replace("完成","实付金额：¥30.00\n完成"),discount.replace("¥20.00\n实付","¥25.00\n实付"),labelled+"\n退款成功",labelled+"\n待对方收款",labelled+"\n验证码123456",labelled+"\n发送",labelled.replace("完成",""),id.replace("2026-11-03","2026-02-30"),"支付成功\n余额\n¥20.00\n完成","支付成功\n订单金额\n¥20.00\n优惠\n¥5.00\n完成"})check(parse(bad)==null,"reject unsafe or ambiguous fixture: "+bad.replace('\n','/'));
        check(WechatCaptureWindow.allowed("com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"),"legacy transfer window preserved");
        for(String name:new String[]{"com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI","com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI","com.tencent.mm.plugin.wallet_core.ui.WalletMixOrderInfoUI","com.tencent.mm.plugin.remittance.ui.RemittanceBusiResultUI","com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI"})check(WechatCaptureWindow.result(name),"verified merchant/scan result window allowed");
        for(String name:new String[]{"com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI","com.tencent.mm.plugin.remittance.ui.RemittanceBusiUI","com.tencent.mm.ui.LauncherUI","android.widget.FrameLayout","some.fake.WalletOrderInfoUI"})check(!WechatCaptureWindow.allowed(name),"input, chat and lookalike class excluded");
        var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        Bitmap bitmap=Bitmap.createBitmap(900,1600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(45);
        String[] rows={"支付成功","¥20.00","实付金额：¥20.00","原价：¥25.00","优惠金额：¥5.00","商户：学校食堂","完成"};
        for(int i=0;i<rows.length;i++)canvas.drawText(rows[i],65,140+i*150,paint);
        Bitmap resized=Bitmap.createScaledBitmap(bitmap,810,1440,true);
        try{
            String recognized=ScreenOcrText.read(Tasks.await(recognizer.process(InputImage.fromBitmap(resized,0)),30,TimeUnit.SECONDS));
            JSONObject fromImage=parse(recognized);
            check(fromImage!=null&&fromImage.getLong("amount")==2000,"bundled Chinese OCR at production resolution parses actual amount");
            boolean merchantRead=recognized.contains("学校食堂");
            check(fromImage.getString("merchant").equals(merchantRead?"学校食堂":""),"recognized merchant is filled; omitted merchant stays unknown");
            result.putString("merchantModelObservation",merchantRead?"merchant recognized":"merchant line omitted by model; field left empty, never invented");
        }finally{recognizer.close();resized.recycle();bitmap.recycle();}
        result.putString("stream","PASS merchant result checks="+checks+"; synthetic layouts + bundled OCR model; "+result.getString("merchantModelObservation")+"; real merchant payment not tested\n");finish(-1,result);
    }catch(Throwable error){result.putString("stream","FAIL after "+checks+": "+error+"\n");finish(1,result);}finally{java.util.TimeZone.setDefault(previousZone);}}
}
