package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * HardwareReceiver — 接收广播，启动HardwareService在Foreground上下文直调硬件。
 * 
 * 接收广播 Intent，action = "com.termux.app.HARDWARE_CAPTURE"
 * 携带参数：
 *   - "type": "photo" / "audio" / "location"
 *   - "output": 输出文件绝对路径
 *   - "duration": 录音时长(秒)，仅audio用，默认5
 * 
 * v2变更（260628）：不再直接在Receiver里调Camera2/MediaRecorder/LocationManager。
 * Android 16限制BroadcastReceiver中启动这些API。
 * 改为启动ForegroundService(HardwareService)，在Service上下文中执行。
 */
public class HardwareReceiver extends BroadcastReceiver {
    private static final String TAG = "HardwareReceiver";
    public static final String ACTION_HARDWARE_CAPTURE = "com.termux.app.HARDWARE_CAPTURE";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_DURATION = "duration";
    public static final String TYPE_PHOTO = "photo";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_LOCATION = "location";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_HARDWARE_CAPTURE.equals(intent.getAction())) {
            return;
        }

        String type = intent.getStringExtra(EXTRA_TYPE);
        String output = intent.getStringExtra(EXTRA_OUTPUT);

        Log.i(TAG, "收到硬件请求: type=" + type + " output=" + output + " → 启动HardwareService");

        if (!TYPE_PHOTO.equals(type) && !TYPE_AUDIO.equals(type) && !TYPE_LOCATION.equals(type)) {
            Log.w(TAG, "未知类型: " + type);
            return;
        }

        // 构建Service Intent，传递所有参数
        Intent serviceIntent = new Intent(context, HardwareService.class);
        serviceIntent.setAction(ACTION_HARDWARE_CAPTURE);
        serviceIntent.putExtra(EXTRA_TYPE, type);
        serviceIntent.putExtra(EXTRA_OUTPUT, output);
        if (TYPE_AUDIO.equals(type)) {
            serviceIntent.putExtra(EXTRA_DURATION, intent.getIntExtra(EXTRA_DURATION, 5));
        }

        // Android O+ 必须用startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
