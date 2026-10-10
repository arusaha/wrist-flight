package dev.wristflight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WristService extends Service implements SensorEventListener {
    public static final String ENABLE = "dev.wristflight.ENABLE";
    public static final String PAUSE = "dev.wristflight.PAUSE";
    public static final String RESUME = "dev.wristflight.RESUME";
    public static final String PROBE = "dev.wristflight.PROBE";
    private static final String CHANNEL = "automation";
    private static final int NOTIFICATION = 1;
    public static volatile WristService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FlightPolicy policy = new FlightPolicy();
    private final Runnable evaluate = this::reconcile;
    private AppPrefs prefs;
    private SensorManager sensors;
    private ShizukuBridge bridge;
    private PowerManager.WakeLock workLock;
    private boolean registered;
    private boolean inFlight;
    private boolean probing;
    private boolean destroyed;
    private boolean intentionalStop;
    private boolean started;
    private int failures;
    private long retryAfter;
    private FlightPolicy.Target lastTarget = FlightPolicy.Target.NONE;
    private volatile FlightPolicy.Target latestTarget = FlightPolicy.Target.NONE;
    private String lastNotification = "";
    private String sensorIssue;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Boolean wasPowered = policy.powered();
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) policy.setPowered(true, now());
            else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) policy.setPowered(false, now());
            else policy.setPowered(FlightPolicy.powerState(
                    intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)), now());
            if (!Boolean.FALSE.equals(wasPowered) && Boolean.FALSE.equals(policy.powered()) && policy.enabled()) refreshSensor();
            reconcile();
        }
    };
    private final ContentObserver airplaneObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { reconcile(); }
    };

    public static boolean launch(Context context, String action) {
        try {
            context.startForegroundService(new Intent(context, WristService.class).setAction(action));
            return true;
        } catch (RuntimeException e) {
            new AppPrefs(context).status("Open Wrist Flight to resume: " + e.getClass().getSimpleName());
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new AppPrefs(this);
        sensors = getSystemService(SensorManager.class);
        workLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristFlight:transition");
        workLock.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Wrist automation", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("Starting…"));
        instance = this;
        bridge = new ShizukuBridge(this, () -> { failures = 0; retryAfter = 0; reconcile(); });
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(battery, filter);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, airplaneObserver);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        String action = intent == null ? RESUME : intent.getAction();
        intentionalStop = false;
        if (ENABLE.equals(action)) {
            if (SensorAccess.issue(this) != null) {
                publish(SensorAccess.issue(this));
                if (!prefs.enabled() && !prefs.restorePending()) finishService();
                return START_NOT_STICKY;
            }
            if (!prefs.setEnabled(true)) {
                publish("Could not save automation preference");
                finishService();
                return START_NOT_STICKY;
            }
        } else if (PAUSE.equals(action)) {
            // Persist recovery before any asynchronous command, including an in-flight ON.
            prefs.setRestorePending(true);
            prefs.setEnabled(false);
        } else if (PROBE.equals(action)) probing = true;

        policy.setEnabled(prefs.enabled(), now());
        if (policy.enabled() && !registered) refreshSensor();
        else if (!policy.enabled()) unregisterSensor();
        failures = 0;
        retryAfter = 0;
        reconcile();
        return prefs.enabled() || prefs.restorePending() ? START_STICKY : START_NOT_STICKY;
    }

    private void refreshSensor() {
        unregisterSensor();
        sensorIssue = SensorAccess.issue(this);
        if (sensorIssue != null) return;
        try {
            registered = sensors.registerListener(this, SensorAccess.sensor(this), SensorManager.SENSOR_DELAY_NORMAL, main);
            if (!registered) sensorIssue = "Watch refused wrist sensor registration";
        } catch (RuntimeException e) { sensorIssue = "Wrist sensor access failed · check Sensors permission"; }
    }

    private void unregisterSensor() {
        if (registered) sensors.unregisterListener(this);
        registered = false;
        policy.setWrist(FlightPolicy.Wrist.UNKNOWN, now());
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!registered || !policy.enabled()) return;
        policy.setWrist(event.values.length == 0 ? FlightPolicy.Wrist.UNKNOWN : FlightPolicy.reading(event.values[0]), now());
        reconcile();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Binary sensor has no meaningful accuracy grade. */ }

    private void reconcile() {
        if (destroyed || bridge == null || !started) return;
        main.removeCallbacks(evaluate);
        if (policy.enabled() && (!SensorAccess.foregroundGranted(this) || !SensorAccess.backgroundGranted(this))) {
            unregisterSensor();
            sensorIssue = "Sensor permission lost · airplane mode must be off";
        }
        long time = now();
        FlightPolicy.Target target = policy.target(time, prefs.restorePending());
        latestTarget = target;
        if (target != lastTarget) { lastTarget = target; failures = 0; retryAfter = 0; }
        if (inFlight) return; // Completion always evaluates the newest inputs.

        if (!bridge.ready()) {
            if (target == FlightPolicy.Target.NONE && !probing) { finishService(); return; }
            bridge.connect();
            publish(ShizukuBridge.authorized() ? "Connecting airplane control…" :
                    "Start Shizuku and grant access · use Quick Settings if airplane mode is on");
            releaseLock();
            if (ShizukuBridge.authorized()) main.postDelayed(evaluate, 15_000);
            else if (probing && !prefs.enabled() && !prefs.restorePending()) { probing = false; finishService(); }
            return;
        }

        // A requested probe only reads state. It never toggles any radios.
        if (probing) {
            probing = false;
            inFlight = true;
            holdLock(15_000);
            IFlightBridge remote = bridge.remote();
            worker.execute(() -> {
                String result;
                try { result = remote.probe(); } catch (Exception e) { result = "Unavailable: " + e.getClass().getSimpleName(); }
                final String report = result;
                main.post(() -> {
                    if (destroyed) return;
                    inFlight = false;
                    publish("Connection check: " + report);
                    if (!prefs.enabled() && !prefs.restorePending()) finishService();
                    else reconcile();
                });
            });
            return;
        }
        if (target == FlightPolicy.Target.NONE) { finishService(); return; }

        Boolean actual = airplaneState(this);
        boolean enable = target == FlightPolicy.Target.ON;
        if (actual != null && actual == enable) {
            if (!enable) prefs.setRestorePending(false);
            if (!policy.enabled()) { publish("Paused · airplane mode off"); finishService(); return; }
            publish(sensorIssue != null ? sensorIssue : policy.reason(time));
            long remaining = policy.confirmationRemaining(time);
            if (remaining > 0) {
                holdLock(remaining + 1500);
                main.postDelayed(evaluate, remaining);
            } else releaseLock();
            return;
        }
        if (failures >= 3) { releaseLock(); return; }
        if (time < retryAfter) { main.postDelayed(evaluate, retryAfter - time); return; }

        // Without a durable recovery marker, never issue ON.
        if (enable && !prefs.setRestorePending(true)) {
            publish("Cannot save recovery state · airplane mode left off");
            releaseLock();
            return;
        }
        inFlight = true;
        holdLock(20_000);
        publish(enable ? "Turning airplane mode on…" : "Turning airplane mode off…");
        IFlightBridge remote = bridge.remote();
        worker.execute(() -> {
            String result;
            // Don't issue queued ON work after a removal/charging/pause event.
            if (enable && latestTarget != FlightPolicy.Target.ON) result = "CANCELLED";
            else {
                try { result = remote.setAirplaneMode(enable); }
                catch (Exception e) { result = "Airplane control lost: " + e.getClass().getSimpleName(); }
            }
            final String outcome = result;
            main.post(() -> commandFinished(enable, outcome));
        });
    }

    private void commandFinished(boolean enabled, String result) {
        if (destroyed) return;
        inFlight = false;
        Boolean actual = airplaneState(this);
        if ("OK".equals(result) && actual != null && actual == enabled) {
            failures = 0; retryAfter = 0;
            if (!enabled) prefs.setRestorePending(false);
            reconcile();
        } else if ("CANCELLED".equals(result)) reconcile();
        else {
            failures++;
            retryAfter = now() + (failures == 1 ? 2000 : 10_000);
            String message = result.length() > 150 ? result.substring(0, 150) : result;
            publish("Control failed · " + ("OK".equals(result) ? "state not confirmed" : message) + " · use Quick Settings");
            // A reversal (especially charging/removal) gets a fresh immediate attempt.
            if ((latestTarget == FlightPolicy.Target.ON) != enabled) { failures = 0; retryAfter = 0; }
            if (failures < 3) main.postDelayed(evaluate, Math.max(0, retryAfter - now()));
            releaseLock();
        }
    }

    private void publish(String message) {
        prefs.status(message);
        if (!message.equals(lastNotification)) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
            lastNotification = message;
        }
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getForegroundService(this, 1, new Intent(this, WristService.class).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_flight)
                .setContentTitle("Wrist Flight").setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
                .addAction(new Notification.Action.Builder(null, "Pause & turn off", pause).build()).build();
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private void holdLock(long millis) { workLock.acquire(millis); }
    private void releaseLock() { if (workLock.isHeld()) workLock.release(); }
    private void finishService() { intentionalStop = true; releaseLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    public FlightPolicy.Wrist wrist() { return policy.wrist(); }
    public Boolean powered() { return policy.powered(); }
    public boolean sensorRegistered() { return registered; }
    public static Boolean airplaneState(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON) == 1; }
        catch (Settings.SettingNotFoundException | SecurityException e) { return null; }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true;
        instance = null;
        main.removeCallbacksAndMessages(null);
        unregisterSensor();
        unregisterReceiver(battery);
        getContentResolver().unregisterContentObserver(airplaneObserver);
        bridge.close();
        worker.shutdownNow();
        releaseLock();
        if (!intentionalStop) prefs.status("Monitoring stopped · reopen Wrist Flight");
        super.onDestroy();
    }
}
