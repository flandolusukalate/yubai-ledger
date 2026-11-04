package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** Dedicated emulator only. Samples never enter a personal ledger. */
public final class AlipayVerificationRunner extends Instrumentation {
    private Bundle args;private int checks;
    private void check(boolean b,String label){if(!b)throw new AssertionError(label);checks++;}
    private JSONObject line(String text,double x,double y,double w)throws Exception{return new JSONObject().put("text",text).put("x",x).put("y",y).put("w",w).put("h",.02);}
    private JSONObject doc(boolean bill,String amount,String status)throws Exception{
        JSONArray lines=new JSONArray();
        if(bill)lines.put(line("账单详情",.05,.06,.2));
        lines.put(line("测试商户",.2,.19,.6)).put(line(amount,.3,.25,.4)).put(line(status,.35,bill?.31:.10,.3));
        if(bill)lines.put(line("支付时间",.05,.36,.2)).put(line("2026-11-01 20:19:01",.30,.36,.6)).put(line("付款方式",.05,.40,.2)).put(line("余额",.30,.40,.2));
        else lines.put(line("完成",.8,.06,.15));
        return new JSONObject().put("width",1000).put("height",2200).put("lines",lines);
    }
    private JSONObject parse(JSONObject d)throws Exception{return AlipayPaymentPage.parse(d,java.time.Instant.parse("2026-11-03T08:00:00Z").toEpochMilli(),"test",AlipayCaptureWindow.BILL,ZoneId.of("Asia/Shanghai"));}
    private JSONObject mspDoc()throws Exception{return new JSONObject().put("lines",new JSONArray()
        .put(line("支付成功",.4,.10,.2)).put(line("￥0.88",.32,.15,.32))
        .put(line("测试店",.06,.25,.3)).put(line("￥1.00",.8,.25,.14))
        .put(line("付款优惠",.06,.28,.3)).put(line("-￥0.12",.78,.28,.16))
        .put(line("付款方式",.06,.32,.2)).put(line("点击给商家对账",.3,.36,.4))
        .put(line("充值金+15",.4,.42,.3)).put(line("2.88元红包",.1,.55,.5)).put(line("完成",.45,.9,.15)));}
    private JSONObject msp(JSONObject doc)throws Exception{return AlipayPaymentPage.parse(doc,System.currentTimeMillis(),"msp",AlipayCaptureWindow.MSP,ZoneId.of("Asia/Shanghai"));}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"dedicated emulator required");
        JSONObject result=parse(doc(false,"¥2.00","支付成功"));check(result!=null&&result.getLong("amount")==200,"complete success page pays 200 fen");
        check(result.getString("origin").equals("alipay")&&result.getString("source").equals("accessibility")&&result.getString("merchant").equals("测试商户"),"source/direction/merchant are explicit");
        JSONObject bill=doc(true,"-2.00","交易成功"),c=parse(bill);check(c!=null&&c.getLong("amount")==200,"negative bill debit with transaction success");
        check(c.getString("occurredAt").equals("2026-11-01T12:19:01Z"),"original payment time uses ledger timezone");
        JSONObject statusGlyph=parse(doc(true,"-0.01","交易戌功"));check(statusGlyph!=null&&statusGlyph.getLong("amount")==1,"observed bill status glyph requires the full outgoing receipt structure");
        check(parse(doc(true,"+0.01","交易戌功"))==null,"status glyph does not turn income into outgoing payment");
        JSONObject backArrow=new JSONObject(bill.toString());backArrow.getJSONArray("lines").getJSONObject(0).put("text","く账单详情");check(parse(backArrow).getLong("amount")==200,"observed OCR back-arrow prefix does not hide the bill header");
        for(String bad:new String[]{"+2.00","2.00","-0.00","-2.001"})check(parse(doc(true,bad,"交易成功"))==null,"invalid or incoming bill rejected");
        for(String bad:new String[]{"支付失败","待付款","处理中","收款成功","交易关闭","退款成功"})check(parse(doc(false,"¥2.00",bad))==null,"non-paid status rejected");
        for(String bad:new String[]{"验证码","输入密码","充值成功","提现","还款成功","借款","退款到账"}){JSONObject d=doc(false,"¥2.00","支付成功");d.getJSONArray("lines").put(line(bad,.1,.5,.8));check(parse(d)==null,"sensitive/internal/refund excluded");}
        JSONObject conflicting=doc(false,"¥2.00","支付成功");conflicting.getJSONArray("lines").put(line("¥3.00",.3,.28,.4));check(parse(conflicting)==null,"multiple plausible paid amounts rejected");
        JSONObject discounted=doc(false,"¥2.00","支付成功");discounted.getJSONArray("lines").put(line("订单金额 3.00",.1,.5,.8));check(parse(discounted)==null,"unlabelled discount ambiguity rejected");
        discounted.getJSONArray("lines").getJSONObject(1).put("text","实付金额 ¥2.00");check(parse(discounted).getLong("amount")==200,"explicit actual paid amount accepted");
        JSONArray ls=bill.getJSONArray("lines");ls.put(line("订单号",.05,.6,.2)).put(line("2026110100000000000000000001",.30,.6,.6)).put(line("商家订单号",.05,.64,.2)).put(line("abcdef0123456789",.30,.64,.6));
        check(parse(bill).getString("reference").equals("alipay:2026110100000000000000000001"),"platform ID does not append merchant order ID");
        JSONObject obscuredMethod=new JSONObject(bill.toString());obscuredMethod.getJSONArray("lines").getJSONObject(6).put("text","付款方武");
        check(parse(obscuredMethod)!=null,"complete platform identity corroborates bill when payment-method label is unreadable");
        JSONObject noIdentity=doc(true,"-2.00","交易成功");noIdentity.getJSONArray("lines").getJSONObject(6).put("text","付款方武");check(parse(noIdentity)==null,"without method or identity keep incomplete bill unrecorded");
        check(!AlipayCaptureWindow.allowed("com.alipay.android.app.flybird.ui.window.FlyBirdWindowActivity"),"cashier/password container is not whitelisted");
        check(!AlipayCaptureWindow.allowed("com.alipay.mobile.payee.ui.PayeeSuccessActivity"),"incoming payee page is not whitelisted");
        check(AlipayPaymentPage.parse(bill,System.currentTimeMillis(),"wrong","com.tencent.mm.ui.LauncherUI",ZoneId.of("Asia/Shanghai"))==null,"wrong application window rejected");
        check(msp(mspDoc()).getLong("amount")==88,"MSP paid 88 fen reconciles 100 less 12 and excludes promotional amounts");
        check(msp(mspDoc()).getString("merchant").equals("测试店"),"merchant from aligned original-price row");
        JSONObject glyphs=mspDoc();glyphs.getJSONArray("lines").getJSONObject(0).put("text","O支付成功");glyphs.getJSONArray("lines").getJSONObject(3).put("text","Y 1.00");glyphs.getJSONArray("lines").getJSONObject(5).put("text","-Y0.12");check(msp(glyphs).getLong("amount")==88,"observed OCR glyph variants retain exact arithmetic");
        JSONObject wrongTotal=mspDoc();wrongTotal.getJSONArray("lines").getJSONObject(1).put("text","￥1.00");check(msp(wrongTotal)==null,"never use original amount as paid amount with discount");
        JSONObject wrongStatus=mspDoc();wrongStatus.getJSONArray("lines").getJSONObject(0).put("text","支付失败");check(msp(wrongStatus)==null,"cashier failed page is not success");
        JSONObject password=mspDoc();password.getJSONArray("lines").put(line("支付密码",.1,.6,.4));check(msp(password)==null,"password text anywhere blocks shared cashier");
        JSONObject noReceipt=mspDoc();noReceipt.getJSONArray("lines").getJSONObject(7).put("text","确认付款");check(msp(noReceipt)==null,"cashier confirmation action is not a completed payment");
        if(args.containsKey("image")){
            var bitmap=android.graphics.BitmapFactory.decodeFile(args.getString("image"));var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try{
                if(args.getString("edge","").equals("1440")){var scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*1440f/bitmap.getHeight()),1440,true);bitmap.recycle();bitmap=scaled;}
                JSONObject actual=RuleOcrDocument.from(Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS),bitmap.getWidth(),bitmap.getHeight());
                boolean live=args.getString("kind","").equals("result");JSONObject payment=live?msp(actual):AlipayPaymentPage.parse(actual,System.currentTimeMillis(),"image",AlipayCaptureWindow.BILL,ZoneId.of("Asia/Shanghai"));
                if(payment==null){try(var output=getTargetContext().openFileOutput("alipay-test-ocr-private.json",0)){output.write(actual.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
                check(payment!=null,"real Alipay image passes strict semantics");check(payment.getLong("amount")==Long.parseLong(args.getString("expected",live?"88":"200")),"real sample actual-paid amount");
                check(live?payment.getString("timeBasis").equals("page-observed-time"):payment.getString("occurredAt").equals(args.getString("occurredAt","2026-11-01T12:19:01Z")),"actual bill date or explicit observation time");check(!payment.getString("merchant").isEmpty(),"real merchant extracted");
                if(args.getString("expanded","").equals("true"))check(payment.getString("reference").length()>8&&payment.getString("reference").length()<45,"expanded platform reference excludes merchant ID");
                out.putString("image",new JSONObject().put("amount",payment.getLong("amount")).put("merchantPresent",!payment.getString("merchant").isEmpty()).put("referenceLength",payment.getString("reference").replaceFirst("^alipay:","").length()).put("timeBasis",payment.getString("timeBasis")).toString());
            }finally{bitmap.recycle();recognizer.close();}
        }
        JSONObject withoutReconciliation=mspDoc();withoutReconciliation.getJSONArray("lines").remove(7);
        JSONObject optionalControl=msp(withoutReconciliation);check(optionalControl!=null&&optionalControl.getLong("amount")==88,"complete paid receipt does not require optional reconciliation button");
        JSONObject lowerCircle=mspDoc();lowerCircle.getJSONArray("lines").getJSONObject(0).put("text","o支付成功");check(msp(lowerCircle)!=null,"real device circle prefix can be lowercase o");
        JSONObject cent=mspDoc();JSONArray centLines=cent.getJSONArray("lines");centLines.remove(7);centLines.remove(5);centLines.remove(4);
        centLines.getJSONObject(1).put("text","¥0.01");centLines.getJSONObject(3).put("text","Y0.01");centLines.getJSONObject(4).put("y",.28);
        check(msp(cent).getLong("amount")==1,"no-discount layout holds exactly one fen and ignores reward amounts");
        JSONObject slashCurrency=new JSONObject(cent.toString());slashCurrency.getJSONArray("lines").getJSONObject(3).put("text","\\0.01");
        JSONObject slashPaid=msp(slashCurrency);check(slashPaid!=null&&slashPaid.getLong("amount")==1,"observed slash currency in merchant row must reconcile to clear headline");
        JSONObject badHeadline=new JSONObject(cent.toString());badHeadline.getJSONArray("lines").getJSONObject(1).put("text","\\0.01");check(msp(badHeadline)==null,"headline currency must remain unambiguous");
        JSONObject secondaryO=new JSONObject(cent.toString());secondaryO.getJSONArray("lines").getJSONObject(3).put("text","YO.01");JSONObject independentlyPaid=msp(secondaryO);
        check(independentlyPaid!=null&&independentlyPaid.getLong("amount")==1,"secondary O may only validate an independently parsed zero digit");
        secondaryO.getJSONArray("lines").getJSONObject(1).put("text","¥0.02");check(msp(secondaryO)==null,"secondary ambiguous glyph cannot hide a differing exact digit");
        JSONObject ambiguousHeadline=new JSONObject(cent.toString());ambiguousHeadline.getJSONArray("lines").getJSONObject(1).put("text","¥O.01");check(msp(ambiguousHeadline)==null,"no monetary value is generated from ambiguous headline digits");
        JSONObject conflict=new JSONObject(cent.toString());conflict.getJSONArray("lines").getJSONObject(3).put("text","¥1.00");check(msp(conflict)==null,"two receipt amounts must agree when there is no discount");
        JSONObject noMethod=new JSONObject(cent.toString());noMethod.getJSONArray("lines").getJSONObject(4).put("text","支付奖励");check(msp(noMethod)==null,"missing payment method remains incomplete");
        JSONObject zero=new JSONObject(cent.toString());zero.getJSONArray("lines").getJSONObject(1).put("text","¥0.00");zero.getJSONArray("lines").getJSONObject(3).put("text","Y0.00");check(msp(zero)==null,"zero is never a payment");
        out.putString("stream","PASS Alipay checks="+checks+" "+out.getString("image","")+"\n");finish(-1,out);
    }catch(Throwable e){out.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(1,out);}}
}
