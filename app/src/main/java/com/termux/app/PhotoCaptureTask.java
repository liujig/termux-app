package com.termux.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * 使用Camera2 API拍照，保存为JPEG。
 * 跑在独立HandlerThread上，不阻塞BroadcastReceiver。
 */
class PhotoCaptureTask {
    private static final String TAG = "PhotoCapture";
    private final Context context;
    private final String outputPath;
    private final ResultReceiver resultReceiver;
    private HandlerThread handlerThread;
    private Handler handler;
    private CameraDevice cameraDevice;

    PhotoCaptureTask(Context context, String outputPath, ResultReceiver resultReceiver) {
        this.context = context;
        this.outputPath = outputPath;
        this.resultReceiver = resultReceiver;
    }

    void execute() {
        handlerThread = new HandlerThread("PhotoCaptureThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            
            // 找后置摄像头
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                // 没有后置就用第一个
                cameraId = manager.getCameraIdList()[0];
            }

            Log.i(TAG, "使用摄像头: " + cameraId);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    takePhoto();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    HardwareReceiver.sendResult(resultReceiver, 1, "摄像头断开");
                    cleanup();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    HardwareReceiver.sendResult(resultReceiver, 1, "摄像头错误: " + error);
                    cleanup();
                }
            }, handler);

        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "打开摄像头失败", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "打开摄像头失败: " + e.getMessage());
            cleanup();
        }
    }

    private void takePhoto() {
        try {
            CameraCharacteristics cc = 
                ((CameraManager) context.getSystemService(Context.CAMERA_SERVICE))
                    .getCameraCharacteristics(cameraDevice.getId());
            
            // 取最大可用尺寸
            android.util.Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(ImageFormat.JPEG);
            android.util.Size photoSize = sizes[sizes.length - 1];
            Log.i(TAG, "照片尺寸: " + photoSize);

            ImageReader imageReader = ImageReader.newInstance(
                photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        saveImage(image);
                        image.close();
                    }
                    reader.close();
                }
            }, handler);

            cameraDevice.createCaptureSession(
                Collections.singletonList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        try {
                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE);
                            builder.addTarget(imageReader.getSurface());
                            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
                            session.capture(builder.build(), null, handler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "拍照失败", e);
                            HardwareReceiver.sendResult(resultReceiver, 1, "拍照失败: " + e.getMessage());
                            cleanup();
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        HardwareReceiver.sendResult(resultReceiver, 1, "配置摄像头失败");
                        cleanup();
                    }
                }, handler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "拍照出错", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "拍照出错: " + e.getMessage());
            cleanup();
        }
    }

    private void saveImage(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File file = new File(outputPath);
            file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();

            Log.i(TAG, "照片已保存: " + outputPath + " (" + bytes.length + " bytes)");
            HardwareReceiver.sendResult(resultReceiver, 0, "照片已保存: " + outputPath);
        } catch (Exception e) {
            Log.e(TAG, "保存照片失败", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "保存照片失败: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }
}
