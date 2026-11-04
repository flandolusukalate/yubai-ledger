package cn.yubai.ledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;

/** Synthetic notification classification only; no listener, database or real payment is used. */
public final class NotificationVerificationRunner extends Instrumentation {
    private int checks;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            check(Build.MODEL.startsWith("sdk_gphone")&&(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish")),"Dedicated emulator required");
            for(String pkg:new String[]{"com.tencent.mm","com.eg.android.AlipayGphone"}){
                String title=pkg.equals("com.tencent.mm")?"微信支付":"支付宝";
                for(String text:new String[]{"付款提醒，请打开应用查看详情","支付后领取红包，更多优惠等你来","转账红包活动，查看详情"})
                    check(PaymentListener.parse(pkg,title,text,"empty",1793505600000L)==null,"Content-free reminder/promotion must not become a debt candidate: "+pkg);
                JSONObject paid=PaymentListener.parse(pkg,title,"支付成功20.00元\n商户：校园食堂\n交易号：paid12345","paid",1793505600000L);
                check(paid.getString("type").equals("expense")&&paid.getLong("amount")==2000&&paid.getBoolean("successful")&&paid.getBoolean("reliable"),"Clear merchant payment remains a reliable expense");
                JSONObject incomplete=PaymentListener.parse(pkg,title,"支付成功，请查看付款详情","incomplete",1793505600000L);
                check(incomplete!=null&&incomplete.isNull("amount")&&!incomplete.getBoolean("reliable"),"Explicit success with missing amount must remain pending");
                JSONObject identity=PaymentListener.parse(pkg,title,"付款详情\n交易号：unknown123","identity",1793505600000L);
                check(identity!=null&&identity.isNull("type")&&!identity.getBoolean("successful")&&!identity.getBoolean("reliable"),"Unknown direction with identity is unknown, not a loan");
                JSONObject unresolvedAmount=PaymentListener.parse(pkg,title,"付款提醒\n支出金额20.00元","amount",1793505600000L);
                check(unresolvedAmount!=null&&unresolvedAmount.getLong("amount")==2000&&!unresolvedAmount.getBoolean("successful")&&!unresolvedAmount.getBoolean("reliable"),"Readable amount without success is retained without auto-booking");
                JSONObject conflict=PaymentListener.parse(pkg,title,"支付成功\n实付20.00元\n实际支付30.00元","conflict",1793505600000L);
                check(conflict!=null&&conflict.isNull("amount")&&!conflict.getBoolean("reliable"),"Conflicting transaction amounts remain pending");
                JSONObject transfer=PaymentListener.parse(pkg,title,"转账成功\n转账金额20.00元\n转账单号：transfer123","transfer",1793505600000L);
                check(transfer.getString("type").equals("complex")&&transfer.getLong("amount")==2000&&!transfer.getBoolean("reliable"),"Transfer purpose must not be promoted to consumption");
                JSONObject failed=PaymentListener.parse(pkg,title,"付款失败，请稍后重试","failed",1793505600000L);
                check(failed!=null&&failed.getString("status").equals("rejected")&&!failed.getBoolean("successful"),"Failed payments remain rejected even without an amount");
                JSONObject waiting=PaymentListener.parse(pkg,title,"付款处理中，请稍候","waiting",1793505600000L);
                check(waiting!=null&&!waiting.getBoolean("successful")&&!waiting.getBoolean("reliable"),"In-progress transaction remains pending");
                JSONObject refund=PaymentListener.parse(pkg,title,"退款成功\n退款金额5.00元","refund",1793505600000L);
                check(refund.getString("type").equals("refund")&&refund.getLong("amount")==500,"Refund is never allowance income");
                JSONObject returned=PaymentListener.parse(pkg,title,"转账已退回，请查看详情","returned",1793505600000L);
                check(returned!=null&&returned.isNull("amount")&&!returned.getBoolean("reliable"),"Explicit returned funds are retained for reconciliation even without amount");
                JSONObject partial=PaymentListener.parse(pkg,title,"付款通知\n实付金额请查看详情","partial",1793505600000L);
                check(partial!=null&&partial.isNull("amount")&&!partial.getBoolean("reliable"),"Recognizable amount role with missing digits remains pending");
                check(PaymentListener.parse(pkg,"普通联系人","支付成功20.00元","chat",0)==null,"Chat title remains excluded");
                check(PaymentListener.parse(pkg,title,"支付验证码123456","sensitive",0)==null,"Sensitive notification remains excluded");
                check(!paid.has("body")&&!paid.has("text")&&!identity.has("body"),"No raw notification text is persisted");
                check(paid.getString("fingerprint").equals(PaymentListener.parse(pkg,title,"支付成功20.00元\n商户：校园食堂\n交易号：paid12345","paid",1793505600000L).getString("fingerprint")),"Same notification keeps stable fingerprint");
            }
            result.putString("status","PASS notification classification checks="+checks);finish(Activity.RESULT_OK,result);
        } catch(Throwable error){result.putString("status","FAIL");result.putInt("passedBeforeFailure",checks);result.putString("error",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
}
