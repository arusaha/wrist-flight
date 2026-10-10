package dev.wristflight;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/** A small, round-screen layout with touch and rotary scrolling. */
public final class MainActivity extends Activity {
    private static final int MINT = Color.rgb(169, 241, 206);
    private static final int MUTED = Color.rgb(166, 177, 171);
    private static final int CHIP_COLOR = Color.rgb(20, 43, 35);
    private static final float PLANE_ANGLE = 45f;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppPrefs prefs;
    private LinearLayout content;
    private ScrollView scroll;
    private String page = "home";
    private TextView headline, chip, status, readings, access, diagnostics;
    private Button toggle;
    private ImageView plane;
    private Boolean planeMonitoring;
    private GradientDrawable chipBackground;
    private AnimatorSet toggleAnimation;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { update(); main.postDelayed(this, 1000); }
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> main.post(() -> {
        if (result == PackageManager.PERMISSION_GRANTED) {
            toast("Airplane control authorized");
            if (prefs.enabled() || prefs.restorePending()) WristService.launch(this, WristService.RESUME);
        } else toast("Access denied. Enable Wrist Flight in Shizuku.");
        update();
    });

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPrefs(this);
        Shizuku.addRequestPermissionResultListener(permission);
        show("home");
    }

    private void show(String destination) {
        resetToggleAnimation();
        page = destination;
        headline = chip = status = readings = access = diagnostics = null;
        toggle = null;
        plane = null;
        planeMonitoring = null;
        chipBackground = null;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.BLACK);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp("home".equals(page) ? 20 : 32), dp(24), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnGenericMotionListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                scroll.smoothScrollBy(0, Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(36)));
                return true;
            }
            return false;
        });
        scroll.setFocusableInTouchMode(true);
        setContentView(scroll);
        if ("home".equals(page)) home();
        else if ("setup".equals(page)) setup();
        else details();
        update();
        scroll.requestFocus();
    }

    private void home() {
        content.setClipChildren(false);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setClipChildren(false);
        brand.setGravity(Gravity.CENTER);
        brand.setMinimumHeight(dp(18));
        plane = new ImageView(this);
        plane.setImageResource(R.drawable.ic_flight_outline);
        plane.setColorFilter(MINT);
        plane.setRotation(PLANE_ANGLE);
        plane.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(16), dp(16));
        icon.setMarginEnd(dp(6));
        brand.addView(plane, icon);
        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setAllCaps(true);
        name.setTextSize(11);
        name.setTextColor(MINT);
        name.setIncludeFontPadding(false);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.addView(name);
        content.addView(brand, new LinearLayout.LayoutParams(-1, -2));
        margin(brand, 0, 8);
        headline = label("Ready to start", 22, Color.WHITE, true);
        headline.setMaxLines(1);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        margin(headline, 4, 4);
        chip = label("AIRPLANE OFF", 11, MINT, true);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        chipBackground = round(CHIP_COLOR, 20);
        chip.setBackground(chipBackground);
        margin(chip, 6, 8);
        toggle = button("Start automation", true, () -> {
            boolean enabling = !prefs.enabled();
            if (enabling) {
                String issue = SensorAccess.issue(this);
                if (issue != null || !ShizukuBridge.authorized()) {
                    toast(issue != null ? issue : "Start Shizuku and grant access first");
                    show("setup");
                    return;
                }
            }
            if (WristService.launch(this, enabling ? WristService.ENABLE : WristService.PAUSE)) {
                animateToggle(enabling);
            }
            main.postDelayed(this::update, 200);
        });
        readings = label("", 12, MUTED, false);
        margin(readings, 8, 14);
        status = label("", 12, MUTED, false);
        margin(status, 0, 12);
        button("Setup", false, () -> show("setup"));
        button("Status & activity", false, () -> show("details"));
        label("On wrist → airplane on\nOff wrist or charging → off", 12, MUTED, false);
    }

    private void setup() {
        label("SETUP", 11, MINT, true);
        label("Make it automatic", 23, Color.WHITE, true);
        label("Install and start Shizuku on this watch first. Restart it after each watch reboot.", 13, MUTED, false);
        access = label("", 12, MINT, false);
        margin(access, 12, 8);
        button("Grant Shizuku access", true, this::requestShizuku);
        button("Open Shizuku", false, () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent == null) toast("Sideload Shizuku on this watch using the setup guide");
            else open(intent);
        });
        label("WRIST DETECTION", 11, MINT, true);
        label("Sensors permission lets the watch report worn or removed. No heart-rate readings are collected.", 13, MUTED, false);
        button("Allow Sensors", false, () -> {
            if (SensorAccess.foregroundGranted(this)) toast("Sensors already allowed");
            else requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 20);
        });
        if (Build.VERSION.SDK_INT >= 33) {
            button("Allow background Sensors", false, () -> {
                if (!SensorAccess.foregroundGranted(this)) { toast("Allow Sensors first"); return; }
                if (SensorAccess.backgroundGranted(this)) { toast("Background Sensors already allowed"); return; }
                // Android requires this separately from the foreground request.
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS_BACKGROUND}, 21);
            });
            label("In App settings → Permissions → Sensors, choose Allow all the time if offered. The install script can grant this for personal sideloading.", 12, MUTED, false);
            button("Allow notifications", false, () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22));
        }
        button("App settings", false, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        label("Allow background running for Wrist Flight and Shizuku in your watch’s battery settings, if available.", 12, MUTED, false);
        button("Check airplane control", false, () -> {
            WristService.launch(this, WristService.PROBE);
            show("details");
        });
        label("This check only reads airplane-mode state.", 12, MUTED, false);
        button("Done", true, () -> show("home"));
    }

    private void details() {
        label("STATUS", 11, MINT, true);
        label("Watch & control", 23, Color.WHITE, true);
        diagnostics = label("", 12, MUTED, false);
        status = label("", 13, Color.WHITE, false);
        margin(status, 12, 12);
        button("Retry / refresh", false, () -> WristService.launch(this,
                prefs.enabled() || prefs.restorePending() ? WristService.RESUME : WristService.PROBE));
        button("Pause & turn off", true, () -> WristService.launch(this, WristService.PAUSE));
        button("Recent activity", false, () -> {
            new android.app.AlertDialog.Builder(this).setTitle("Recent activity")
                    .setMessage(prefs.history().isEmpty() ? "No events yet" : prefs.history())
                    .setPositiveButton("Close", null).show();
        });
        label("If control is unavailable, turn airplane mode off in Quick Settings. Airplane mode follows the watch’s saved Wi-Fi and Bluetooth preferences.", 12, MUTED, false);
        button("Back", false, () -> show("home"));
        label("Wrist Flight " + BuildConfig.VERSION_NAME + "\nPersonal • on-device only", 11, MUTED, false);
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) { toast("Start Shizuku on the watch first"); return; }
            if (ShizukuBridge.authorized()) { toast("Shizuku access is ready"); return; }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Open Shizuku → Authorized applications → Wrist Flight");
                return;
            }
            Shizuku.requestPermission(10);
        } catch (RuntimeException e) { toast("Shizuku unavailable. Start it and try again."); }
    }

    private void update() {
        if (prefs == null) return;
        WristService service = WristService.instance;
        Boolean airplane = WristService.airplaneState(this);
        boolean active = prefs.enabled() && service != null;
        if (plane != null && (planeMonitoring == null || planeMonitoring != active)) {
            plane.setImageResource(active ? R.drawable.ic_flight : R.drawable.ic_flight_outline);
            planeMonitoring = active;
        }
        if (headline != null) {
            String title = "Ready to start";
            if (prefs.enabled() && service == null) title = "Needs attention";
            else if (active) {
                if (!ShizukuBridge.authorized() || !service.sensorRegistered()) title = "Needs attention";
                else if (Boolean.TRUE.equals(service.powered())) title = "Charging";
                else if (service.wrist() == FlightPolicy.Wrist.ON) title = "On your wrist";
                else if (service.wrist() == FlightPolicy.Wrist.OFF) title = "Off your wrist";
                else title = "Listening…";
            }
            headline.setText(title);
        }
        if (chip != null) chip.setText(airplane == null ? "AIRPLANE UNKNOWN" : airplane ? "AIRPLANE ON" : "AIRPLANE OFF");
        if (status != null) {
            String message = prefs.status();
            status.setText(message);
            boolean duplicatePausedStatus = "home".equals(page) && !prefs.enabled() && "Automation paused".equals(message);
            status.setVisibility(duplicatePausedStatus ? View.GONE : View.VISIBLE);
        }
        if (toggle != null) toggle.setText(prefs.enabled() ? "Pause & turn off" : "Start automation");
        if (readings != null) readings.setText(active ? "Monitoring enabled" : prefs.enabled() ? "Monitoring unavailable" : "Automation paused");
        if (access != null) access.setText("Shizuku: " + (ShizukuBridge.authorized() ? "authorized" : "not ready")
                + "\nSensors: " + (SensorAccess.foregroundGranted(this) ? "allowed" : "needed")
                + "\nBackground: " + (SensorAccess.backgroundGranted(this) ? "allowed" : "needed"));
        if (diagnostics != null) {
            Sensor sensor = SensorAccess.sensor(this);
            diagnostics.setText("Model: " + Build.MODEL + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\nAirplane: " + (airplane == null ? "unknown" : airplane ? "on" : "off")
                    + "\nShizuku: " + (ShizukuBridge.authorized() ? "authorized" : "not ready")
                    + "\nMonitor: " + (active ? "running" : "stopped")
                    + "\nWrist: " + (service == null ? "unknown" : service.wrist().name().toLowerCase(java.util.Locale.ROOT))
                    + "\nCharger: " + (service == null || service.powered() == null ? "unknown" : service.powered() ? "connected" : "disconnected")
                    + "\nSensor: " + (sensor == null ? "unavailable" : sensor.getName())
                    + "\nSensor registered: " + (service != null && service.sensorRegistered() ? "yes" : "no")
                    + "\nRecovery marker: " + (prefs.restorePending() ? "set" : "clear"));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
        if (prefs.enabled() || prefs.restorePending()) WristService.launch(this, WristService.RESUME);
    }
    @Override protected void onPause() { main.removeCallbacks(ticker); resetToggleAnimation(); super.onPause(); }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        resetToggleAnimation();
        Shizuku.removeRequestPermissionResultListener(permission);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (!"home".equals(page)) show("home"); else super.onBackPressed();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        update();
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        margin(view, 5, 5);
        return view;
    }
    private Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(primary ? Color.rgb(13, 36, 26) : Color.WHITE);
        button.setBackground(round(primary ? MINT : Color.rgb(32, 38, 35), 28));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        margin(button, 4, 4);
        return button;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }
    private void animateToggle(boolean enabling) {
        resetToggleAnimation();
        // The system disables these for reduced motion / zero animator duration.
        if (!ValueAnimator.areAnimatorsEnabled() || toggle == null) return;
        ObjectAnimator bounce = ObjectAnimator.ofPropertyValuesHolder(toggle,
                PropertyValuesHolder.ofFloat(View.SCALE_X, .96f, 1.015f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, .96f, 1.015f, 1f));
        bounce.setDuration(320);
        ObjectAnimator flight = ObjectAnimator.ofPropertyValuesHolder(plane,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dp(enabling ? 6 : -5), 0f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dp(enabling ? -8 : 6), 0f),
                PropertyValuesHolder.ofFloat(View.ROTATION, PLANE_ANGLE, PLANE_ANGLE + (enabling ? -12 : 12), PLANE_ANGLE));
        flight.setDuration(460);
        ObjectAnimator title = ObjectAnimator.ofPropertyValuesHolder(headline,
                PropertyValuesHolder.ofFloat(View.ALPHA, .45f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dp(4), 0f));
        title.setDuration(320);
        ValueAnimator highlight = ValueAnimator.ofArgb(Color.rgb(53, 96, 76), CHIP_COLOR);
        GradientDrawable background = chipBackground;
        highlight.addUpdateListener(animation -> background.setColor((int) animation.getAnimatedValue()));
        highlight.setDuration(420);
        ObjectAnimator chipScale = ObjectAnimator.ofPropertyValuesHolder(chip,
                PropertyValuesHolder.ofFloat(View.SCALE_X, .98f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, .98f, 1f));
        chipScale.setDuration(420);
        toggleAnimation = new AnimatorSet();
        toggleAnimation.setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f));
        toggleAnimation.playTogether(bounce, flight, title, highlight, chipScale);
        toggleAnimation.start();
    }
    private void resetToggleAnimation() {
        if (toggleAnimation != null) {
            toggleAnimation.cancel();
            toggleAnimation = null;
        }
        for (View view : new View[]{toggle, plane, headline, chip}) {
            if (view == null) continue;
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationX(0f);
            view.setTranslationY(0f);
            view.setAlpha(1f);
        }
        if (plane != null) plane.setRotation(PLANE_ANGLE);
        if (chipBackground != null) chipBackground.setColor(CHIP_COLOR);
    }
    private void margin(View view, int top, int bottom) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(top); params.bottomMargin = dp(bottom);
        view.setLayoutParams(params);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException e) { toast("This screen is unavailable on this firmware. Use the setup guide."); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
