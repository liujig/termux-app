package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

/**
 * HardwareReceiver — 在Termux进程context内处理相机拍照和麦克风录音。
 * 
 * 接收广播 Intent，action = "com.termux.app.HARDWARE_CAPTURE"
 * 携带参数：
 *   - "type": "photo" 或 "audio"
 *   - "output": 输出文件绝对路径
 *   - "duration": 录音时长(秒)，仅audio用，默认5
 *   - "resultReceiver": 可选，android.os.ResultReceiver，操作完成后回调
 */
public class HardwareReceiver extends BroadcastReceiver {
    private static final String TAG = "HardwareReceiver";
    public static final String ACTION_HARDWARE_CAPTURE = "com.termux.app.HARDWARE_CAPTURE";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String TYPE_PHOTO = "photo";
    public static final String TYPE_AUDIO = "audio";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_HARDWARE_CAPTURE.equals(intent.getAction())) {
            return;
        }

        String type = intent.getStringExtra(EXTRA_TYPE);
        String output = intent.getStringExtra(EXTRA_OUTPUT);
        ResultReceiver resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        Log.i(TAG, "收到硬件请求: type=" + type + " output=" + output);

        if (TYPE_PHOTO.equals(type)) {
            new PhotoCaptureTask(context, output, resultReceiver).execute();
        } else if (TYPE_AUDIO.equals(type)) {
            int duration = intent.getIntExtra(EXTRA_DURATION, 5);
            new AudioRecordTask(context, output, duration, resultReceiver).execute();
        } else {
            sendResult(resultReceiver, 2, "未知类型: " + type);
        }
    }

    static void sendResult(ResultReceiver receiver, int code, String message) {
        if (receiver != null) {
            Bundle bundle = new Bundle();
            bundle.putString("message", message);
            receiver.send(code, bundle);
        }
    }
}
