package cn.yubai.ledger;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** No raw title/body/chat text is persisted. Unknown notifications are immediately discarded. */
public final class PaymentListener extends NotificationListenerService {
    public static volatile boolean connected=false;
    private Store store;
    @Override public void onCreate(){super.onCreate();store=new Store(this);}
    @Override public void onListenerConnected(){connected=true;getSharedPreferences("collector",0).edit().putString("error","").apply();}
    @Override public void onListenerDisconnected(){connected=false;}
    @Override public void onDestroy(){connected=false;if(store!=null)store.close();super.onDestroy();}
    static String match(String text,String regex) {Matcher m=Pattern.compile(regex).matcher(text);return m.find()?m.group(1):"";}
    static String digest(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format("%02x",x));return b.toString();
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg=sbn.getPackageName();
            if(!pkg.equals("com.tencent.mm")&&!pkg.equals("com.eg.android.AlipayGphone"))return;
            String saved=store.load();if(saved.isEmpty())return;
            JSONObject settings=new JSONObject(saved).getJSONObject("settings");JSONArray sources=settings.getJSONArray("sources");boolean enabled=false;
            for(int i=0;i<sources.length();i++)if(pkg.equals(sources.getString(i)))enabled=true;
            if(!enabled)return;
            Notification n=sbn.getNotification();if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
            String title=String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE,""));
            CharSequence big=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String body=String.valueOf(big!=null?big:n.extras.getCharSequence(Notification.EXTRA_TEXT,""));
            JSONObject c=parse(pkg,title,body,sbn.getKey(),sbn.getPostTime());
            if(c==null)return;
            store.enqueue(c);AutoLedgerProcessor.schedule(this);getSharedPreferences("collector",0).edit().putString("last",Instant.now().toString()).putString("error","").apply();
        } catch(Exception ex) {getSharedPreferences("collector",0).edit().putString("error","通知解析异常："+ex.getClass().getSimpleName()).apply();}
    }
    static JSONObject parse(String pkg,String title,String body,String key,long postTime) throws Exception {
            if(!pkg.equals("com.tencent.mm")&&!pkg.equals("com.eg.android.AlipayGphone"))return null;
            // Exact service-title allowlist; regular conversation titles are discarded.
            if(!title.matches("微信支付|支付宝|支付助手|收款到账通知|付款通知|交易提醒"))return null;
            if(Pattern.compile("验证码|动态密码|密码|口令").matcher(body).find())return null;
            if(!Pattern.compile("支付|付款|收款|退款|转账|红包").matcher(body).find())return null;
            String origin=pkg.equals("com.tencent.mm")?"wechat":"alipay";
            boolean failed=Pattern.compile("支付失败|付款失败|交易失败|转账失败|已取消|已关闭|退款失败").matcher(body).find();
            boolean waiting=Pattern.compile("待领取|待收款|等待[^\\n]*(?:领取|收款|收钱)|处理中|待支付|退款中|退款申请").matcher(body).find();
            boolean complex=Pattern.compile("转账|红包|充值|提现|还款|借款|代付|报销").matcher(body).find();
            boolean transfer=body.contains("转账")&&!body.contains("红包");
            String type=Pattern.compile("退款成功|退款到账|已退回").matcher(body).find()?"refund":Pattern.compile("收款到账|收款成功|已收款").matcher(body).find()?"income":Pattern.compile("支付成功|付款成功|实付").matcher(body).find()?"expense":"unknown";
            String roles=type.equals("refund")?"退款金额|退款到账":type.equals("income")?"收款金额|收款到账|收入金额":"实付金额|实际支付|实付|支付成功|付款成功|支出金额";
            if(transfer&&!type.equals("refund"))roles+="|转账金额|转出金额|转账成功";
            Matcher amounts=Pattern.compile("(?:"+roles+")[\\s:：人民币￥¥]*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.,])").matcher(body);
            Long amount=null;boolean conflict=false;
            while(amounts.find()) {
                long next=new BigDecimal(amounts.group(1)).movePointRight(2).longValueExact();
                if(next<=0||next>1_000_000_000_000L){conflict=true;continue;}
                if(amount!=null&&amount!=next)conflict=true;amount=next;
            }
            if(conflict)amount=null;
            boolean successful=(Pattern.compile("支付成功|付款成功|收款到账|收款成功|已收款|退款成功|退款到账").matcher(body).find()||(transfer&&Pattern.compile("转账成功|对方已收钱|对方已收款").matcher(body).find()))&&!failed&&!waiting;
            String ref=match(body,(type.equals("refund")?"(?:退款单号|退款交易号)":"(?:交易单号|交易号|转账单号)")+"\\s*[:：]?\\s*([A-Za-z0-9_-]{6,80})");
            // A service title plus a payment keyword is also used for generic
            // reminders and promotions. With no transaction field or explicit
            // state, there is nothing useful to ask the user to reconcile.
            // Keep incomplete/failed transactions and conflicting amounts;
            // never infer an expense simply to avoid showing a pending item.
            if(amount==null&&ref.isEmpty()&&type.equals("unknown")&&!successful&&!failed&&!waiting&&!conflict)return null;
            String merchant=match(body,"(?:商户|商家|收款方)\\s*[:：]\\s*([^\\n]{1,60})");
            String category=merchant.matches(".*(餐|食堂|咖啡|奶茶|外卖).*")?"food":merchant.matches(".*(地铁|公交|打车|交通).*")?"transport":merchant.matches(".*(电影|游戏|运动).*")?"fun":merchant.matches(".*(超市|日用品).*")?"goods":"";
            JSONObject c=new JSONObject();String timestamp=Instant.ofEpochMilli(postTime).toString();
            c.put("id",java.util.UUID.randomUUID().toString());c.put("source","notification");c.put("origin",origin);
            // Post time and normalized extracted fields distinguish reused notification IDs. No original message stored.
            c.put("fingerprint",digest(pkg+"|"+key+"|"+timestamp+"|"+ref+"|"+amount+"|"+type+"|"+successful+"|"+failed+"|"+waiting));
            c.put("reference",ref.isEmpty()?"":origin+":"+ref);c.put("amount",amount==null?JSONObject.NULL:amount);
            c.put("type",complex?"complex":type.equals("unknown")?JSONObject.NULL:type);c.put("category",category.isEmpty()?JSONObject.NULL:category);c.put("merchant",merchant);
            c.put("occurredAt",timestamp);c.put("collectedAt",Instant.now().toString());c.put("timeBasis","notification-post-time");
            c.put("status",failed?"rejected":"pending");c.put("successful",successful);
            c.put("reliable",successful&&!complex&&amount!=null);
            c.put("reason",failed?"支付失败或取消，不入账":waiting?"交易尚未完成，请核对本人实际资金状态":transfer&&successful&&amount!=null?"已识别转账金额。用于消费请选择「消费」并核对预算；借款、代付等继续待核对":complex?"通知包含转账、红包或其他用途信息，具体类型尚未确认，请核对实际账单":amount==null||type.equals("unknown")||!successful?"通知中的金额、收支方向或成功状态不完整，请核对实际账单":"通知时间为近似发生时间，请核对分类与预算");
            return c;
    }
}
