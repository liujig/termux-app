package com.termux.app;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 使用 LocationManager 获取GPS/网络定位，输出JSON到文件。
 * 跑在独立HandlerThread上，不阻塞BroadcastReceiver。
 * 
 * 策略：同时请求GPS和Network provider，10秒内取最优结果。
 */
class LocationTask {
    private static final String TAG = "LocationTask";
    private static final long TIMEOUT_SECONDS = 10;

    private final Context context;
    private final String outputPath;
    private final ResultReceiver resultReceiver;
    private HandlerThread handlerThread;
    private Handler handler;
    private LocationManager locationManager;
    private Location bestLocation;

    LocationTask(Context context, String outputPath, ResultReceiver resultReceiver) {
        this.context = context;
        this.outputPath = outputPath;
        this.resultReceiver = resultReceiver;
    }

    void execute() {
        handlerThread = new HandlerThread("LocationThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        handler.post(this::startLocation);
    }

    private void startLocation() {
        try {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                HardwareReceiver.sendResult(resultReceiver, 1, "LocationManager不可用");
                cleanup();
                return;
            }

            // 先取缓存定位
            Location gpsCached = null;
            Location netCached = null;
            try {
                gpsCached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (SecurityException e) {
                Log.w(TAG, "GPS缓存权限不足: " + e.getMessage());
            }
            try {
                netCached = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
                Log.w(TAG, "网络定位缓存权限不足: " + e.getMessage());
            }
            bestLocation = pickBest(gpsCached, netCached);

            Log.i(TAG, "GPS缓存: " + (gpsCached != null ? gpsCached.getLatitude() + "," + gpsCached.getLongitude() : "无"));
            Log.i(TAG, "网络缓存: " + (netCached != null ? netCached.getLatitude() + "," + netCached.getLongitude() : "无"));

            // 如果缓存定位够新（30秒内），直接用
            if (bestLocation != null && 
                System.currentTimeMillis() - bestLocation.getTime() < 30_000) {
                saveLocation(bestLocation, "cached");
                return;
            }

            // 否则请求实时定位
            final CountDownLatch latch = new CountDownLatch(1);
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    Log.i(TAG, "实时定位: " + location.getProvider() + " " 
                        + location.getLatitude() + "," + location.getLongitude() 
                        + " 精度:" + location.getAccuracy() + "m");
                    if (pickBest(bestLocation, location) == location) {
                        bestLocation = location;
                    }
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.d(TAG, provider + " 已关闭");
                }

                @Override
                public void onProviderEnabled(String provider) {
                    Log.d(TAG, provider + " 已开启");
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            try {
                // 同时请求GPS和网络定位
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0, 0, listener, handlerThread.getLooper());
                    Log.i(TAG, "已请求GPS实时定位");
                } else {
                    Log.w(TAG, "GPS未开启，仅用网络定位");
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0, 0, listener, handlerThread.getLooper());
                    Log.i(TAG, "已请求网络实时定位");
                }

                // 等待超时或拿到满意精度（<20m）
                long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
                while (System.currentTimeMillis() < deadline) {
                    if (bestLocation != null) {
                        long ageMs = System.currentTimeMillis() - bestLocation.getTime();
                        if (bestLocation.getAccuracy() < 20 || ageMs < 5000) {
                            // 精度够好或者刚拿到的最新定位
                            break;
                        }
                    }
                    Thread.sleep(500);
                }

            } catch (SecurityException e) {
                Log.e(TAG, "定位权限不足", e);
                HardwareReceiver.sendResult(resultReceiver, 1, "定位权限不足: " + e.getMessage());
                locationManager.removeUpdates(listener);
                cleanup();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locationManager.removeUpdates(listener);
            }

            if (bestLocation != null) {
                saveLocation(bestLocation, "live");
            } else {
                HardwareReceiver.sendResult(resultReceiver, 1, "定位超时: " + TIMEOUT_SECONDS + "秒内未获取到位置");
                cleanup();
            }

        } catch (Exception e) {
            Log.e(TAG, "定位异常", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "定位失败: " + e.getMessage());
            cleanup();
        }
    }

    private Location pickBest(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        // 优先GPS provider，其次看精度
        if (LocationManager.GPS_PROVIDER.equals(a.getProvider()) && 
            !LocationManager.GPS_PROVIDER.equals(b.getProvider())) {
            return a;
        }
        if (LocationManager.GPS_PROVIDER.equals(b.getProvider()) && 
            !LocationManager.GPS_PROVIDER.equals(a.getProvider())) {
            return b;
        }
        // 同provider比精度
        return a.getAccuracy() <= b.getAccuracy() ? a : b;
    }

    private void saveLocation(Location location, String source) {
        try {
            JSONObject json = new JSONObject();
            json.put("latitude", location.getLatitude());
            json.put("longitude", location.getLongitude());
            json.put("accuracy", location.getAccuracy());
            json.put("altitude", location.getAltitude());
            json.put("speed", location.getSpeed());
            json.put("bearing", location.getBearing());
            json.put("provider", location.getProvider());
            json.put("time", location.getTime());
            json.put("source", source);

            File file = new File(outputPath);
            file.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(file);
            fw.write(json.toString(2));
            fw.close();

            Log.i(TAG, "定位已保存: " + outputPath);
            HardwareReceiver.sendResult(resultReceiver, 0, "定位完成: " + outputPath 
                + " (" + location.getProvider() + " " + location.getLatitude() + "," + location.getLongitude() + ")");
        } catch (Exception e) {
            Log.e(TAG, "保存定位失败", e);
            HardwareReceiver.sendResult(resultReceiver, 1, "保存定位失败: " + e.getMessage());
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
