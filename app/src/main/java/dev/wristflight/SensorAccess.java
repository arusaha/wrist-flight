package dev.wristflight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

public final class SensorAccess {
    private SensorAccess() {}
    public static boolean foregroundGranted(Context context) {
        return context.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
    }
    public static boolean backgroundGranted(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.BODY_SENSORS_BACKGROUND) == PackageManager.PERMISSION_GRANTED;
    }
    public static Sensor sensor(Context context) {
        return context.getSystemService(SensorManager.class).getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true);
    }
    public static String issue(Context context) {
        if (!foregroundGranted(context)) return "Allow Sensors permission";
        if (!backgroundGranted(context)) return "Allow Sensors all the time";
        if (sensor(context) == null) return "No wake-up wrist sensor exposed by this watch";
        return null;
    }
}
