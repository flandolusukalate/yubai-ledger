package cn.yubai.ledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded request after local failure. No automatic network retries, no image/response logging. */
final class DeepSeekFallback {
    static final String MODEL="deepseek-flash",ENDPOINT="https://api.deepseek.com/chat/completions";
    private static final java.util.concurrent.ExecutorService NETWORK=Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    interface Transport { JSONObject call(String secret,JSONObject body)throws Exception; }
    static byte[] image(Bitmap bitmap)throws Exception {
        double scale=Math.min(1,2880.0/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        Bitmap resized=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*scale)),Math.max(1,(int)(bitmap.getHeight()*scale)),true);
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();resized.compress(Bitmap.CompressFormat.JPEG,85,out);byte[] data=out.toByteArray();if(data.length>6_000_000)throw new IllegalArgumentException("付款图片过大");return data;}
        finally{if(resized!=bitmap)resized.recycle();}
    }
    static JSONObject body(byte[] jpeg)throws Exception {
        if(jpeg.length==0||jpeg.length>6_000_000)throw new IllegalArgumentException("付款图片无效");
        return new JSONObject().put("model",MODEL).put("thinking",new JSONObject().put("type","disabled"))
            .put("response_format",new JSONObject().put("type","json_object")).put("max_tokens",700).put("stream",false)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",DeepSeekReceipt.PROMPT))
                .put(new JSONObject().put("role","user").put("content",new JSONArray()
                    .put(new JSONObject().put("type","text").put("text","提取这张付款图片的字段，返回规定的 JSON。"))
                    .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/jpeg;base64,"+Base64.encodeToString(jpeg,Base64.NO_WRAP)).put("detail","original"))))));
    }
    static JSONObject answer(JSONObject response)throws Exception {
        JSONArray choices=response.getJSONArray("choices");if(choices.length()!=1)throw new IllegalArgumentException("AI 返回结果数量异常");
        JSONObject choice=choices.getJSONObject(0);if(!choice.optString("finish_reason").equals("stop"))throw new IllegalArgumentException("AI 返回被截断，未生成账单");
        JSONObject message=choice.getJSONObject("message");Object raw=message.opt("content");
        if(message.has("tool_calls")||!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>4096)throw new IllegalArgumentException("AI 返回为空或格式异常");
        String json=((String)raw).trim();if(!json.startsWith("{")||!json.endsWith("}"))throw new IllegalArgumentException("AI 未返回 JSON 对象");
        org.json.JSONTokener tokener=new org.json.JSONTokener(json);JSONObject result=new JSONObject(tokener);if(tokener.nextClean()!=0)throw new IllegalArgumentException("AI 返回多余内容");return result;
    }
    static JSONObject http(String secret,JSONObject body)throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ENDPOINT).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");connection.setRequestProperty("Authorization","Bearer "+secret);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setDoOutput(true);
        byte[] request=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(request.length);
        var deadline=TIMER.schedule(connection::disconnect,30,TimeUnit.SECONDS);
        try{
            try(var out=connection.getOutputStream()){out.write(request);}
            int status=connection.getResponseCode();
            if(status!=200)throw new IllegalArgumentException(switch(status){case 401,403->"DeepSeek 密钥或访问权限无效";case 402->"DeepSeek 账户余额不足";case 429->"DeepSeek 请求受限，请稍后重试";default->"DeepSeek 服务请求失败（HTTP "+status+"）";});
            try(var input=connection.getInputStream();var bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int size;while((size=input.read(buffer))!=-1){if(bytes.size()+size>131072)throw new IllegalArgumentException("AI 响应过大");bytes.write(buffer,0,size);}
                return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{deadline.cancel(false);connection.disconnect();}
    }
    private static void state(Context context,String state,String message,long ms){
        DeepSeekSettings.prefs(context).edit().putString("state",state).putString("message",message).putLong("lastMs",ms).putString("lastAt",Instant.now().toString()).apply();
    }
    static boolean shouldCall(boolean enabled,boolean localFailed,boolean safe,boolean current){return enabled&&localFailed&&safe&&current;}
    static JSONObject evaluate(Transport transport,String secret,byte[] jpeg,JSONObject doc,String origin,String imageKey,long now,java.time.ZoneId zone)throws Exception {
        return DeepSeekReceipt.candidate(answer(transport.call(secret,body(jpeg))),doc,origin,imageKey,now,zone);
    }
    // Callers supply only a failed local read. The supplied task lifetime cancels on opt-out or invalidation; captured windows may outlive navigation.
    static boolean request(Context context,byte[] jpeg,JSONObject document,String origin,String imageKey,boolean localFailed,boolean window,BooleanSupplier current,Consumer<JSONObject> done,Consumer<String> failed)throws Exception {
        return request(context,jpeg,document,origin,imageKey,localFailed,window,current,done,failed,DeepSeekFallback::http);
    }
    static boolean request(Context context,byte[] jpeg,JSONObject document,String origin,String imageKey,boolean localFailed,boolean window,BooleanSupplier current,Consumer<JSONObject> done,Consumer<String> failed,Transport transport)throws Exception {
        return request(context,jpeg,document,origin,imageKey,localFailed,window,current,done,failed,transport,null);
    }
    static boolean requestCaptured(Context context,byte[] jpeg,JSONObject document,CapturedPayment frame,String imageKey,BooleanSupplier current,Consumer<JSONObject> done,Consumer<String> failed)throws Exception{
        return requestCaptured(context,jpeg,document,frame,imageKey,current,done,failed,DeepSeekFallback::http);
    }
    static boolean requestCaptured(Context context,byte[] jpeg,JSONObject document,CapturedPayment frame,String imageKey,BooleanSupplier current,Consumer<JSONObject> done,Consumer<String> failed,Transport transport)throws Exception{
        return request(context,jpeg,document,frame.origin,imageKey,true,true,current,done,failed,transport,frame);
    }
    private static boolean request(Context context,byte[] jpeg,JSONObject document,String origin,String imageKey,boolean localFailed,boolean window,BooleanSupplier current,Consumer<JSONObject> done,Consumer<String> failed,Transport transport,CapturedPayment frame)throws Exception {
        Context app=context.getApplicationContext();if(!shouldCall(DeepSeekSettings.enabled(app),localFailed,DeepSeekReceipt.safeImage(document,window),current.getAsBoolean()))return false;
        if(!BUSY.compareAndSet(false,true)){failed.accept("已有 DeepSeek 请求正在处理，请稍后再试");return true;}
        long revision=DeepSeekSettings.revision(app),now=System.currentTimeMillis();var p=DeepSeekSettings.prefs(app);
        String day=Instant.ofEpochMilli(now).atZone(CustomRuleRegistry.zone(app)).toLocalDate().toString();
        int count=day.equals(p.getString("day",""))?p.getInt("count",0):0;
        if(now-p.getLong("attemptAt",0)<15_000||count>=50){BUSY.set(false);failed.accept(count>=50?"今日云端识别已达 50 次，本地功能照常可用":"云端请求间隔至少 15 秒，请稍后重试");return true;}
        if(!p.edit().putString("day",day).putInt("count",count+1).putLong("attemptAt",now).commit()){BUSY.set(false);failed.accept("无法保存请求状态，未联网");return true;}
        JSONObject doc=new JSONObject(document.toString());byte[] bytes=jpeg.clone();
        state(app,"running","本地未识别，正在请求 DeepSeek；尚未扣款",0);
        NETWORK.execute(()->{
            long start=android.os.SystemClock.elapsedRealtime();
            try{
                if(!current.getAsBoolean()||!DeepSeekSettings.enabled(app)||revision!=DeepSeekSettings.revision(app)){
                    if(revision==DeepSeekSettings.revision(app))state(app,"discarded","识别任务已取消，未发送云端请求",0);return;
                }
                JSONObject candidate=evaluate(transport,DeepSeekSettings.secret(app),bytes,doc,origin,imageKey,frame==null?now:frame.observedAt,frame==null?CustomRuleRegistry.zone(app):frame.zone);
                if(!current.getAsBoolean()||!DeepSeekSettings.enabled(app)||revision!=DeepSeekSettings.revision(app)){if(revision==DeepSeekSettings.revision(app))state(app,"discarded","识别任务已取消，云端结果已丢弃",android.os.SystemClock.elapsedRealtime()-start);return;}
                state(app,window?"interpreted":"review",window?"DeepSeek 已返回，正在检查付款与重复记录":"DeepSeek 已返回，截图补记需核对",android.os.SystemClock.elapsedRealtime()-start);done.accept(candidate);
            }catch(Exception error){
                String message=error instanceof IllegalArgumentException?error.getMessage():"DeepSeek 连接失败或超时；未扣款，可手动记账";
                if(current.getAsBoolean()&&revision==DeepSeekSettings.revision(app)){state(app,"error",message,android.os.SystemClock.elapsedRealtime()-start);failed.accept(message);}
            }finally{java.util.Arrays.fill(bytes,(byte)0);BUSY.set(false);}
        });return true;
    }
}
