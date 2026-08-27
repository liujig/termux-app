package com.termux.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置传感器HTTP服务 - 无需Termux:API
 * 在localhost:18888提供传感器数据
 */
public class BuiltinSensorServer implements SensorEventListener {

    private static final int PORT = 18888;
    private static BuiltinSensorServer instance;

    private SensorManager sensorManager;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final ConcurrentHashMap<Integer, float[]> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> names = new ConcurrentHashMap<>();

    public static synchronized void start(Context context) {
        if (instance == null) {
            instance = new BuiltinSensorServer();
            instance.init(context);
        }
    }

    private void init(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        registerSensors();
        startServer();
    }

    private void registerSensors() {
        int[] types = {
            Sensor.TYPE_LIGHT, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE,
            Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION
        };
        for (int type : types) {
            Sensor s = sensorManager.getDefaultSensor(type);
            if (s != null) {
                names.put(type, sensorName(type));
                sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    private String sensorName(int type) {
        switch (type) {
            case 1: return "accelerometer";
            case 2: return "magnetic";
            case 4: return "gyroscope";
            case 5: return "light";
            case 6: return "pressure";
            case 8: return "proximity";
            case 9: return "gravity";
            case 10: return "linear_acceleration";
            case 12: return "humidity";
            case 13: return "temperature";
            case 15: return "rotation";
            case 19: return "steps";
            case 21: return "heart_rate";
            default: return "sensor_" + type;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        values.put(event.sensor.getType(), event.values.clone());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startServer() {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    while (running) {
                        final Socket client = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() { handle(client); }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();
    }

    private void handle(Socket client) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String req = r.readLine();
            if (req == null) { client.close(); return; }
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {}

            String path = req.split(" ")[1];
            String body;

            switch (path) {
                case "/list":
                    body = listJson();
                    break;
                case "/sensors":
                case "/sensors/all":
                    body = allJson();
                    break;
                default:
                    if (path.startsWith("/sensors/")) {
                        body = oneJson(path.substring(9));
                    } else {
                        body = "{\"error\":\"unknown\"}";
                    }
            }

            byte[] b = body.getBytes("UTF-8");
            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(hdr.getBytes());
            out.write(b);
            out.flush();
            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String listJson() {
        StringBuilder sb = new StringBuilder("{\"sensors\":[");
        boolean first = true;
        for (Map.Entry<Integer, String> e : names.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"type\":").append(e.getKey()).append(",\"name\":\"").append(e.getValue()).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private String allJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, float[]> e : values.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(names.getOrDefault(e.getKey(), "s"+e.getKey())).append("\":{\"values\":[");
            float[] v = e.getValue();
            for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(","); sb.append(v[i]); }
            sb.append("]}");
        }
        return sb.append("}").toString();
    }

    private String oneJson(String name) {
        Integer type = nameToType(name);
        if (type == null) return "{\"error\":\"unknown sensor\"}";
        float[] v = values.get(type);
        if (v == null) return "{\"name\":\"" + name + "\",\"values\":[]}";
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name).append("\",\"values\":[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(","); sb.append(v[i]); }
        return sb.append("]}").toString();
    }

    private Integer nameToType(String name) {
        switch (name) {
            case "light": return 5;
            case "accelerometer": case "accel": return 1;
            case "gyroscope": case "gyro": return 4;
            case "magnetic": case "compass": return 2;
            case "proximity": return 8;
            case "pressure": return 6;
            case "temperature": return 13;
            case "humidity": return 12;
            case "steps": return 19;
            case "heart_rate": return 21;
            case "rotation": return 15;
            case "gravity": return 9;
            case "linear_acceleration": return 10;
            default:
                try { return Integer.parseInt(name); } catch (Exception e) { return null; }
        }
    }
}
