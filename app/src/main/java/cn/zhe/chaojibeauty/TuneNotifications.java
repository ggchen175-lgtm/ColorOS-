package cn.zhe.chaojibeauty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.UUID;

/** USB fixtures are posted under the shell's own identity. Clear only our tagged entries. */
public final class TuneNotifications {
    private static final String PREFIX="chaoji.live.test/";
    private static final String TRIP="chaoji_live_trips_v1", CHAT="chaoji_live_chat_v1";
    private final Context shell;
    private final NotificationManager manager;
    private final Object service;
    private final java.lang.reflect.Method enqueue,cancel;
    private final int user;
    private final String batch=PREFIX+UUID.randomUUID();
    private int id,children;

    private TuneNotifications(Context shell)throws Exception{
        this.shell=shell;
        manager=shell.getSystemService(NotificationManager.class);
        service=NotificationManager.class.getMethod("getService").invoke(null);
        Class<?> api=Class.forName("android.app.INotificationManager");
        enqueue=api.getMethod("enqueueNotificationWithTag",String.class,String.class,String.class,int.class,Notification.class,int.class);
        cancel=api.getMethod("cancelNotificationWithTag",String.class,String.class,String.class,int.class,int.class);
        user=(int)android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
    }
    private static String unpack(String text){
        return new String(Base64.decode(text,Base64.URL_SAFE),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception{
        if(args.length==0 || android.os.Process.myUid()!=2000)return;
        Looper.prepareMainLooper();
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Object at=thread.getMethod("systemMain").invoke(null);
        Context system=(Context)thread.getMethod("getSystemContext").invoke(at);
        TuneNotifications test=new TuneNotifications(system.createPackageContext("com.android.shell",0));
        if("clear".equals(args[0])){System.out.println(test.clear());return;}
        if(args.length!=6 || !"post".equals(args[0]))throw new IllegalArgumentException("unknown test");
        int count=Math.max(1,Math.min(30,Integer.parseInt(args[1])));
        String title=unpack(args[2]),body=unpack(args[3]),mode=args[4],routes=unpack(args[5]);
        if(test.shell.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("USB shell 缺少通知权限");
        test.channel(TRIP,"测试 · 车次提醒");
        test.channel(CHAT,"测试 · 消息");
        if("routes".equals(mode)){
            LinkedHashSet<String> trains=new LinkedHashSet<>();
            for(String s:routes.split("[,，、\\s]+"))if(!s.trim().isEmpty())trains.add(s.trim());
            if(trains.isEmpty() || trains.size()*count>30)throw new IllegalArgumentException("确认车次，最多 30 条");
            for(String train:trains)test.group(TRIP,train+" · "+title,body,count);
        }else if("both".equals(mode)){
            if(count>15)throw new IllegalArgumentException("两种类型合计最多 30 条");
            String train=routes.split("[,，、\\s]+")[0];
            test.group(TRIP,train+" · 行程提醒",body,count);
            test.group(CHAT,"聊天消息 · "+title,body,count);
        }else if("stack".equals(mode)){
            if(count<2)throw new IllegalArgumentException("堆叠至少需要两条子通知");
            test.group(CHAT,title,body,count);
        }else if("single".equals(mode)){
            for(int i=1;i<=count;i++)test.group(CHAT,title,body,1);
        }else throw new IllegalArgumentException("unknown style");
        System.out.println(test.children);
    }
    private void channel(String id,String name){
        NotificationChannel channel=new NotificationChannel(id,name,NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null,null);channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }
    private Notification.Builder face(String channel,String title,String body,String group){
        boolean trip=TRIP.equals(channel);
        return new Notification.Builder(shell,channel)
            .setSmallIcon(trip?android.R.drawable.ic_menu_directions:android.R.drawable.ic_dialog_email)
            .setCategory(trip?Notification.CATEGORY_EVENT:Notification.CATEGORY_MESSAGE)
            .setGroup(group).setContentTitle(title).setContentText(body)
            .setShowWhen(true).setWhen(System.currentTimeMillis())
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true);
    }
    // ActivityThread.systemMain supplies an Android system context. Attribute both
    // package arguments to our actual shell UID, including cancellation.
    private void post(Notification notice)throws Exception{
        enqueue.invoke(service,shell.getPackageName(),shell.getPackageName(),batch,++id,notice,user);
    }
    private void group(String channel,String title,String body,int count)throws Exception{
        String group=batch+"/"+id;
        if(count>1)post(face(channel,title,count+" 条通知",group)
            .setStyle(new Notification.InboxStyle().addLine(body)).setGroupSummary(true).build());
        for(int i=1;i<=count;i++){
            String label=count==1?title:title+" · "+i;
            post(face(channel,label,body,group)
                .setStyle(new Notification.BigTextStyle().bigText(body)).build());
            children++;
            if(i>=5 && i<count && i%5==0)android.os.SystemClock.sleep(200);
        }

    }
    private int clear()throws Exception{
        int count=0;
        for(StatusBarNotification sbn:manager.getActiveNotifications()){
            if(sbn.getTag()==null || !sbn.getTag().startsWith(PREFIX))continue;
            if((sbn.getNotification().flags&Notification.FLAG_GROUP_SUMMARY)==0)count++;
            cancel.invoke(service,shell.getPackageName(),shell.getPackageName(),sbn.getTag(),sbn.getId(),user);
        }
        return count;
    }
}
