package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Parser regression only. Does not access or change the user's ledger. */
public final class TransferDetailVerificationRunner extends Instrumentation {
    private int checks;
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        String text="待测试收款人3.17收款\n¥0.01\n1天内对方未收款，将退还给你。提醒对方收款\n转账时间\n2026年11月02日 18:04:48\n账单详情";
        List<String> lines=Arrays.asList(text.split("\\n"));
        JSONObject c=PageCaptureService.parsePage("com.tencent.mm",lines,1793617200000L,"first");
        check(c!=null&&c.getLong("amount")==1,"actual pending-detail structure must be accepted by accessibility entry point");
        check(c.getString("source").equals("accessibility")&&c.getString("direction").equals("out"),"source and outgoing direction");
        check(c.getBoolean("successful")&&c.getString("fundsStatus").equals("paid_out")&&c.getString("recipientStatus").equals("pending"),"funded sender independent from recipient acceptance");
        check(c.getString("occurredAt").equals("2026-11-02T10:04:48Z"),"actual transfer time is preserved");
        check(!c.toString().contains("测试收款人")&&!c.has("text"),"only digest, not recipient or raw text, is retained");
        JSONObject refresh=PageCaptureService.parsePage("com.tencent.mm",lines,1793620000000L,"first");
        JSONObject revisit=PageCaptureService.parsePage("com.tencent.mm",lines,1793620000000L,"second");
        check(c.getString("fingerprint").equals(refresh.getString("fingerprint")),"same visit is idempotent");
        check(!c.getString("fingerprint").equals(revisit.getString("fingerprint")),"no transaction ID: separate visit is not silently merged");
        check(c.getString("paymentEvidenceKey").equals(revisit.getString("paymentEvidenceKey")),"revisited receipt keeps duplicate-suspicion key");
        JSONObject success=PageCaptureService.parsePage("com.tencent.mm",Arrays.asList("支付成功","待测试收款人3.17确认收款","¥0.01","完成"),1793620000000L,"success");
        JSONObject screen=ScreenOcrParser.parse(text,1793620000000L,"screen");
        check(c.getString("paymentEvidenceKey").equals(success.getString("paymentEvidenceKey")),"success and detail share suspicion across observed timestamps");
        check(c.getString("paymentEvidenceKey").equals(screen.getString("screenEvidenceKey")),"screen OCR and accessibility share suspicion");
        for(String bad:new String[]{text.replace("待测试收款人3.17收款","待你收款"),text.replace("将退还给你","将退还给对方"),text.replace("2026年11月02日","2026年02月30日"),text+"\n余额 ¥30.00",text+"\n发送",text+"\n已退回",text+"\n验证码123456"})
            check(PageCaptureService.parsePage("com.tencent.mm",Arrays.asList(bad.split("\\n")),1793617200000L,"bad")==null,"reject incoming, refund, private, ambiguous and invalid-date pages");
        check(PageCaptureService.parsePage("other.app",lines,0,"bad")==null,"only selected WeChat origin");
        result.putString("stream","PASS transfer detail parser checks="+checks+"; synthetic page structure, no ledger writes\n");finish(-1,result);
    }catch(Throwable error){result.putString("stream","FAIL "+error.getMessage()+"\n");finish(1,result);}}
}
