package cn.yubai.ledger;

import android.app.NotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Optional visual feedback only. Notification permission never gates OCR or accounting. */
final class CapturePrompt {
    private static final String CHANNEL="payment_capture_ready";
    private static final int ID=2401;
    static boolean enabled(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!manager.areNotificationsEnabled())return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static void show(Context context){
        // A notification failure must never crash or cancel a captured payment.
        try{
            if(!enabled(context))return;
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            NotificationChannel channel=new NotificationChannel(CHANNEL,"付款图已截取",NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("截图完成后提示可离开付款页面；提示不代表记账成功");
            channel.setSound(null,null);channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            PendingIntent open=PendingIntent.getActivity(context,ID,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification prompt=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("已截取付款图，可离开页面").setContentText("后台继续识别；记账结果请在余白核对")
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(12_000).setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).build();
            manager.notify(ID,prompt);
            context.getSharedPreferences("collector",0).edit().putString("capture_prompt_state","posted").apply();
        }catch(RuntimeException unavailable){
            context.getSharedPreferences("collector",0).edit().putString("capture_prompt_state","unavailable").apply();
        }
    }
}
