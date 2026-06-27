package com.termux.app;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 使用MediaRecorder录音，输出AAC/M4A格式。
 * 跑在独立HandlerThread上，不阻塞BroadcastReceiver。
 */
class AudioRecordTask {
    private static final String TAG = "AudioRecord";
    private final Context context;
    private final String outputPath;
    private final int durationSeconds;
    private final ResultReceiver resultReceiver;
    private HandlerThread handlerThread;
    private Handler handler;
    private MediaRecorder recorder;

    AudioRecordTask(Context context, String outputPath, int durationSeconds, ResultReceiver resultReceiver) {
        this.context = context;
        this.outputPath = outputPath;
        this.durationSeconds = Math.max(1, Math.min(durationSeconds, 300)); // 1-300秒
        this.resultReceiver = resultReceiver;
    }

    void execute() {
        handlerThread = new HandlerThread("AudioRecordThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        handler.post(this::startRecording);
    }

    private void startRecording() {
        try {
            File file = new File(outputPath);
            file.getParentFile().mkdirs();

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(file.getAbsolutePath());

            recorder.prepare();
            recorder.start();

            Log.i(TAG, "开始录音: " + outputPath + " 时长: " + durationSeconds + "秒");

            // 定时停止
            handler.postDelayed(this::stopRecording, TimeUnit.SECONDS.toMillis(durationSeconds));

        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "录音启动失败", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "录音启动失败: " + e.getMessage());
            cleanup();
        }
    }

    private void stopRecording() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
            File file = new File(outputPath);
            Log.i(TAG, "录音完成: " + outputPath + " (" + file.length() + " bytes)");
            HardwareReceiver.sendResult(resultReceiver, 0, "录音完成: " + outputPath);
        } catch (Exception e) {
            Log.e(TAG, "停止录音失败", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "停止录音失败: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }
}
