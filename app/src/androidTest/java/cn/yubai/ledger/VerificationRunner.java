package cn.yubai.ledger;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** Synthetic instrumented checks. These do NOT claim real WeChat / Alipay coverage. */
public class VerificationRunner extends Instrumentation {
    int checks=0;
    void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        JSONObject c=PaymentListener.parse("com.tencent.mm","微信支付","支付成功20.00元\n商户：学校食堂\n交易号：12345678","notice-1",1793505600000L);
        check(c.getLong("amount")==2000,"integer actual payment");check(c.getBoolean("reliable"),"clear success");check(c.getString("category").equals("food"),"merchant category");
        check(!c.has("body")&&!c.has("text"),"no raw message retention");
        check(PaymentListener.parse("com.tencent.mm","同学","支付成功20.00元","chat",0)==null,"discard chat title");
        check(PaymentListener.parse("com.tencent.mm","微信支付","验证码123456 支付成功20.00元","otp",0)==null,"discard OTP anywhere");
        check(PaymentListener.parse("unknown","微信支付","支付成功20.00元","unknown",0)==null,"reject unknown package");
        JSONObject failed=PaymentListener.parse("com.tencent.mm","微信支付","支付失败 实付20.00元","failed",0);
        check(failed.getString("status").equals("rejected")&&!failed.getBoolean("reliable"),"failure excluded");
        check(!PaymentListener.parse("com.tencent.mm","微信支付","支付成功20.00元 处理中","pending",0).getBoolean("reliable"),"pending excluded");
        check(PaymentListener.parse("com.tencent.mm","微信支付","实付1,200.00元 支付成功","comma",0).isNull("amount"),"never truncate comma amount");
        check(PaymentListener.parse("com.tencent.mm","微信支付","转账 收款到账20.00元","transfer",0).getString("type").equals("complex"),"transfer pending");
        JSONObject transfer=PaymentListener.parse("com.tencent.mm","微信支付","转账成功\n转账金额：20.00元\n转账单号：transfer123","transfer-complete",0);
        check(transfer.getLong("amount")==2000&&transfer.getBoolean("successful"),"transfer labeled amount and completed state");
        check(transfer.getString("type").equals("complex")&&!transfer.getBoolean("reliable"),"transfer requires purpose confirmation");
        check(transfer.getString("reference").equals("wechat:transfer123"),"transfer identity links status updates");
        check(PaymentListener.parse("com.tencent.mm","微信支付","转账\n对方已收钱\n转出金额128.50","transfer-received",0).getLong("amount")==12850,"recipient completion explicit outgoing amount");
        check(!PaymentListener.parse("com.tencent.mm","微信支付","转账成功 等待对方收款\n转账金额20.00","transfer-waiting",0).getBoolean("successful"),"waiting recipient is not completed");
        check(PaymentListener.parse("com.tencent.mm","微信支付","转账失败\n转账金额20.00","transfer-failed",0).getString("status").equals("rejected"),"transfer failure rejected");
        check(PaymentListener.parse("com.tencent.mm","微信支付","转账成功\n转账金额20.00\n转出金额30.00","transfer-conflict",0).isNull("amount"),"transfer amount conflict remains unknown");
        check(PaymentListener.parse("com.tencent.mm","同学","转账成功\n转账金额20.00","transfer-chat",0)==null,"transfer parsing does not broaden chat collection");
        check(PaymentListener.parse("com.eg.android.AlipayGphone","支付宝","退款成功 退款金额5.00元","refund",0).getString("type").equals("refund"),"refund not income");
        java.util.List<String> transferPage=java.util.Arrays.asList("微信转账","¥20.00","对方已收钱","转账时间","2026-11-01 12:00:00","转账单号","detail12345");
        JSONObject page=PageCaptureService.parsePage("com.tencent.mm",transferPage,1793505600000L,"view-1");
        check(page!=null&&page.getLong("amount")==2000,"detail header assigns single nearby currency amount");
        check(page.getString("source").equals("accessibility")&&!page.getBoolean("reliable")&&page.getString("type").equals("complex"),"page transfer is always untrusted pending purpose");
        check(page.getBoolean("successful")&&page.getString("reference").equals("wechat:detail12345"),"detail state and identity retained");
        check(page.getString("timeBasis").equals("page-local-time-device-zone"),"labeled occurrence time kept separate from collection");
        check(!page.has("text")&&!page.has("body")&&page.getString("merchant").isEmpty(),"no raw page or recipient text retained");
        check(PageCaptureService.parsePage("unknown",transferPage,0,"x")==null,"page source whitelist");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("张同学","转账成功","转账金额20.00","转账时间","2026-11-01 12:00:00"),0,"x")==null,"chat lacks exact detail header");
        java.util.List<String> chatPage=new java.util.ArrayList<>(transferPage);chatPage.add("发送");check(PageCaptureService.parsePage("com.tencent.mm",chatPage,0,"x")==null,"chat composing context rejected");
        java.util.List<String> sensitivePage=new java.util.ArrayList<>(transferPage);sensitivePage.add("验证码123456");check(PageCaptureService.parsePage("com.tencent.mm",sensitivePage,0,"x")==null,"sensitive page rejected");
        JSONObject duplicate=PageCaptureService.parsePage("com.tencent.mm",transferPage,1793505601000L,"view-2");check(page.getString("fingerprint").equals(duplicate.getString("fingerprint")),"same detail identity survives repeated collection time");
        JSONObject waitingPage=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("转账详情","转账金额20.00","等待对方收款","转账时间","2026-11-01 12:00:00"),0,"x");check(!waitingPage.getBoolean("successful")&&!waitingPage.getBoolean("reliable"),"waiting page never completed");
        JSONObject balancePage=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("微信转账","余额","¥2000.00","对方已收钱","转账时间","2026-11-01 12:00:00"),0,"x");check(balancePage.isNull("amount"),"balance currency never adopted as transfer");
        JSONObject conflictPage=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("微信转账","¥20.00","¥30.00","对方已收钱","转账时间","2026-11-01 12:00:00"),0,"x");check(conflictPage.isNull("amount"),"multiple unlabeled amounts remain ambiguous");
        JSONObject badDatePage=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("转账详情","转账金额20.00","转账成功","转账时间","2026-02-30 12:00:00"),0,"x");check(badDatePage.getString("timeBasis").equals("page-observed-time"),"invalid page date cannot normalize into another day");
        JSONObject failedPage=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("转账详情","转账金额20.00","转账失败","转账单号","failed123"),0,"x");check(failedPage.getString("status").equals("rejected")&&!failedPage.getBoolean("successful"),"failed detail never completed");
        check(MainActivity.containsComponent("cn.yubai.ledger/.PageCaptureService",new android.content.ComponentName(getTargetContext(),PageCaptureService.class)),"short flattened accessibility grant recognized");
        java.util.List<String> noIdPage=java.util.Arrays.asList("转账详情","转账金额20.00","转账成功","转账时间","2026-11-01 12:00:00");
        JSONObject visitOne=PageCaptureService.parsePage("com.tencent.mm",noIdPage,0,"window-a"),visitTwo=PageCaptureService.parsePage("com.tencent.mm",noIdPage,0,"window-b");
        check(!visitOne.getString("fingerprint").equals(visitTwo.getString("fingerprint")),"same amount second without ID never silently collapses separate page visits");
        check(visitOne.getString("fingerprint").equals(PageCaptureService.parsePage("com.tencent.mm",noIdPage,1000,"window-a").getString("fingerprint")),"same page session refresh is idempotent without ID");
        java.util.List<String> paidResultLines=java.util.Arrays.asList("支付成功","待收款人确认收款","￥5.20","完成");
        JSONObject paidResult=PageCaptureService.parsePage("com.tencent.mm",paidResultLines,1793602800000L,"result-a");
        check(paidResult!=null&&paidResult.getLong("amount")==520,"sender payment result without detail header preserves 520 fen");
        check(paidResult.getString("fundsStatus").equals("paid_out")&&paidResult.getString("recipientStatus").equals("pending")&&paidResult.getString("direction").equals("out"),"sender paid and recipient waiting are independent states");
        check(paidResult.getBoolean("successful")&&!paidResult.getBoolean("reliable")&&paidResult.getString("type").equals("complex")&&paidResult.getString("status").equals("pending"),"paid result still requires purpose confirmation");
        check(paidResult.getString("evidence").equals("wechat-paid-transfer-result-v1"),"strict transfer evidence marker supports explicit one-purchase policy");
        check(paidResult.getString("merchant").isEmpty()&&!paidResult.toString().contains("待收款人确认收款"),"recipient nickname and raw result line not retained");
        check(paidResult.getString("timeBasis").equals("page-observed-time")&&paidResult.getString("reference").isEmpty(),"missing result time and reference never invented");
        check(paidResult.getString("fingerprint").equals(PageCaptureService.parsePage("com.tencent.mm",paidResultLines,1793602801000L,"result-a").getString("fingerprint")),"same payment result refresh has stable fingerprint");
        check(!paidResult.getString("fingerprint").equals(PageCaptureService.parsePage("com.tencent.mm",paidResultLines,1793602801000L,"result-b").getString("fingerprint")),"new no-ID result visit does not silently merge a second transfer");
        check(PageCaptureService.parsePage("other.app",paidResultLines,0,"a")==null,"payment result rejects wrong source");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","￥5.20","完成"),0,"a")==null,"bare success and currency do not imply a transfer");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待你确认收款","￥5.20","完成"),0,"a")==null,"recipient waiting page never inferred as outgoing payment");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","￥5.20","￥50.00","完成"),0,"a")==null,"multiple result amounts rejected rather than selecting largest");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","￥5.200","完成"),0,"a")==null,"malformed result currency not truncated");
        JSONObject decimalResult=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","5.20","完成"),0,"a");
        check(decimalResult!=null&&decimalResult.getLong("amount")==520&&!decimalResult.getBoolean("reliable"),"strict payment result accepts unique two-decimal token when currency glyph is absent");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","5","完成"),0,"a")==null,"bare integer never inferred as transfer amount");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","￥5.20","50.00","完成"),0,"a")==null,"bare decimal conflicts with currency amount");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","5.20","50.00","完成"),0,"a")==null,"multiple bare decimals never select a largest result amount");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","1,200.00","完成"),0,"a")==null,"unsupported grouped decimal not truncated");
        JSONObject splitGlyphResult=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","待收款人确认收款","¥","5.20","完成"),0,"a");
        check(splitGlyphResult!=null&&splitGlyphResult.getLong("amount")==520,"currency and decimal in separate nodes retain sender payment evidence");
        for(String forbidden:java.util.Arrays.asList("支付失败","已取消","处理中","已退回","退款金额","余额","红包总额","发送","验证码123456")){
            java.util.List<String> invalidResult=new java.util.ArrayList<>(paidResultLines);invalidResult.add(forbidden);
            check(PageCaptureService.parsePage("com.tencent.mm",invalidResult,0,"a")==null,"result conflicts or sensitive/chat contexts rejected: "+forbidden);
        }
        java.util.List<String> merchantResultLines=java.util.Arrays.asList("支付成功","实付金额：¥20.00","商户：学校食堂","完成");
        JSONObject merchantResult=PageCaptureService.parsePage("com.tencent.mm",merchantResultLines,1793602800000L,"merchant-a");
        check(merchantResult!=null&&merchantResult.getLong("amount")==2000,"explicit merchant actual payment has integer amount");
        check(merchantResult.getString("evidence").equals("wechat-paid-merchant-result-v1")&&merchantResult.getString("direction").equals("out")&&merchantResult.getString("fundsStatus").equals("paid_out")&&merchantResult.getString("type").equals("expense")&&merchantResult.getBoolean("reliable"),"merchant result role and direction evidence");
        check(merchantResult.getString("source").equals("accessibility")&&merchantResult.getString("status").equals("pending")&&merchantResult.getString("timeBasis").equals("page-observed-time"),"merchant result remains candidate until core applies policy");
        check(!merchantResult.has("text")&&!merchantResult.has("body")&&merchantResult.getString("merchant").equals("学校食堂"),"merchant result saves only extracted business fields");
        check(merchantResult.getString("fingerprint").equals(PageCaptureService.parsePage("com.tencent.mm",merchantResultLines,1793602801000L,"merchant-a").getString("fingerprint")),"merchant page session refresh is idempotent");
        check(!merchantResult.getString("fingerprint").equals(PageCaptureService.parsePage("com.tencent.mm",merchantResultLines,1793602801000L,"merchant-b").getString("fingerprint")),"no-ID merchant visit remains separate evidence");
        JSONObject splitMerchantResult=PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","实付金额","¥20.00","收款方","学校食堂","完成"),0,"merchant-a");
        check(splitMerchantResult!=null&&splitMerchantResult.getLong("amount")==2000,"explicit merchant labels may occupy adjacent nodes");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","¥20.00","商户：学校食堂","完成"),0,"merchant-a")==null,"merchant payment without actual amount role not inferred");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","实付金额：¥20.00","完成"),0,"merchant-a")==null,"merchant payment without merchant role not inferred");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","实付金额：¥20.00","商户：学校食堂"),0,"merchant-a")==null,"incomplete merchant result rejected");
        check(PageCaptureService.parsePage("com.tencent.mm",java.util.Arrays.asList("支付成功","实付金额：¥20.00","实付金额：¥30.00","商户：学校食堂","完成"),0,"merchant-a")==null,"multiple actual payment roles rejected");
        check(PageCaptureService.parsePage("other.app",merchantResultLines,0,"merchant-a")==null,"merchant source remains WeChat-only");
        for(String forbidden:java.util.Arrays.asList("支付失败","退款金额","余额","¥50.00","发送","验证码123456","转账","收入金额","待领取","已收款")){
            java.util.List<String> invalidMerchantResult=new java.util.ArrayList<>(merchantResultLines);invalidMerchantResult.add(forbidden);
            check(PageCaptureService.parsePage("com.tencent.mm",invalidMerchantResult,0,"merchant-a")==null,"merchant conflicting state or amount rejected: "+forbidden);
        }
        Context isolated=new ContextWrapper(getTargetContext()){
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath("verification-"+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("verification-"+name,mode,factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return super.openOrCreateDatabase("verification-"+name,mode,factory,handler);}
        };
        getTargetContext().deleteDatabase("verification-yubai.db");
        Store store=new Store(isolated);String one="{\"version\":1,\"test\":\"first\"}";store.save(one);store.enqueue(c);store.enqueue(c);check(new JSONArray(store.inbox()).length()==1,"queue fingerprint unique");store.close();
        store=new Store(isolated);check(store.load().equals(one),"SQLite snapshot survives reopen");JSONArray queue=new JSONArray(store.inbox());check(queue.length()==1,"pending queue survives reopen");
        store.replace("{\"version\":1,\"test\":\"second\"}");check(store.recovery().equals(one),"restore keeps pre-restore snapshot");
        String current=store.load();try{store.replace("invalid JSON");throw new AssertionError("invalid accepted");}catch(org.json.JSONException expected){}check(store.load().equals(current),"failed restore atomic rollback");
        store.acknowledge(new JSONArray().put(queue.getJSONObject(0).getLong("queueId")).toString());check(new JSONArray(store.inbox()).length()==0,"queue acknowledgment");store.close();
        Bitmap bitmap=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(56);
        canvas.drawText("支付成功",60,120,paint);canvas.drawText("实付金额 20.00 元",60,230,paint);canvas.drawText("商户：学校食堂",60,340,paint);canvas.drawText("交易号：12345678",60,450,paint);canvas.drawText("2026-11-01 12:00:00",60,560,paint);
        java.io.File fixture=new java.io.File(getTargetContext().getExternalFilesDir(null),"ocr-fixture.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        String text=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),45,TimeUnit.SECONDS).getText();recognizer.close();
        check(text.contains("20.00"),"bundled OCR actual digits");check(text.contains("支付成功"),"bundled OCR Chinese");
        result.putString("ocrText",text);result.putString("fixture",fixture.getAbsolutePath());result.putInt("passed",checks);result.putString("status","PASS: synthetic Android parser, SQLite and bundled Chinese OCR");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("status","FAIL");result.putString("error",android.util.Log.getStackTraceString(e));result.putInt("passedBeforeFailure",checks);finish(Activity.RESULT_CANCELED,result);}}
}
