package cn.yubai.ledger;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;
import org.json.JSONObject;

/** Selection regression with the real payment parser; no personal database or permissions changed. */
public final class PageCaptureVerificationRunner extends Instrumentation {
    int checks;
    void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        List<String> complete=List.of("待测试收款人收款","¥0.01","1天内对方未收款，将退还给你。提醒对方收款","转账时间","2026年11月02日 18:04:48","账单详情");
        PageReadPipeline.Parser<JSONObject> parser=lines->PageCaptureService.parsePage("com.tencent.mm",lines,1793617200000L,"selection");
        JSONObject c=PageReadPipeline.read(()->complete,List::of,parser);
        check(c!=null&&c.getLong("amount")==1,"read event source when root is empty");
        int[] roots={0};c=PageReadPipeline.read(()->complete,()->{roots[0]++;return List.of();},parser);
        check(c!=null&&roots[0]==0,"complete source avoids second traversal");
        c=PageReadPipeline.read(()->List.of("¥0.01"),()->complete,parser);
        check(c!=null&&c.getLong("amount")==1,"partial source falls back to complete root");
        check(PageReadPipeline.read(()->List.of("支付成功"),()->List.of("待测试人确认收款","¥0.01","完成"),parser)==null,"partial trees are never stitched into a false payment");
        check(PageReadPipeline.read(List::of,List::of,parser)==null,"both empty never invent a zero payment");
        c=PageReadPipeline.read(()->{throw new IllegalStateException("stale event node");},()->complete,parser);
        check(c!=null,"stale source still permits root fallback");
        out.putString("stream","PASS source/root selection checks="+checks+"; synthetic node text, real parser\n");finish(-1,out);
    }catch(Throwable e){out.putString("stream","FAIL "+e.getMessage()+"; passed="+checks+"\n");finish(1,out);}}
}
