package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/**
 * HardwareService — 前台服务，在Foreground上下文中直调相机/录音/GPS。
 * 解决Android 16 BroadcastReceiver无法直接调用Camera2/MediaRecorder的限制。
 * 
 * 接收Intent参数：
 *   - "type": "photo" / "audio" / "location"
 *   - "output": 输出文件路径
 *   - "duration": 录音时长(秒)，仅audio用
 */
public class HardwareService extends Service {
    private static final String TAG = "HardwareService";
    private static final String CHANNEL_ID = "hardware_channel";
    private static final int NOTIFICATION_ID = 1001;

    private HandlerThread handlerThread;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("HardwareServiceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String type = intent.getStringExtra("type");
        String output = intent.getStringExtra("output");
        int duration = intent.getIntExtra("duration", 5);

        Log.i(TAG, "启动前台服务: type=" + type + " output=" + output);

        // 显示前台通知
        String label;
        switch (type != null ? type : "") {
            case "photo": label = "拍照中..."; break;
            case "audio": label = "录音中..."; break;
            case "location": label = "定位中..."; break;
            default: label = "硬件访问中...";
        }
        startForeground(NOTIFICATION_ID, buildNotification(label));

        // 在后台线程执行硬件操作
        final String finalType = type;
        final String finalOutput = output;
        final int finalDuration = duration;
        handler.post(() -> {
            try {
                if ("photo".equals(finalType)) {
                    new PhotoCaptureTask(this, finalOutput, null).execute();
                    updateNotification("拍照完成");
                } else if ("audio".equals(finalType)) {
                    new AudioRecordTask(this, finalOutput, finalDuration, null).execute();
                    updateNotification("录音完成");
                } else if ("location".equals(finalType)) {
                    new LocationTask(this, finalOutput, null).execute();
                    updateNotification("定位完成");
                }
            } catch (Exception e) {
                Log.e(TAG, "硬件操作失败", e);
                updateNotification("操作失败: " + e.getMessage());
            }

            // 给异步Task留时间完成（拍照/定位是异步的）
            handler.postDelayed(this::stopSelf, 15000);
        });

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "硬件访问", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Termux直调相机/麦克风/GPS时显示");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("Termux")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
        Log.i(TAG, text);
    }
}
