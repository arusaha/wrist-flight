package dev.wristflight;

/** Pure decision logic; no persisted wrist readings and no wall-clock time. */
public final class FlightPolicy {
    public enum Wrist { UNKNOWN, OFF, ON }
    public enum Target { NONE, OFF, ON }
    public static final long CONFIRM_MS = 3000;

    private boolean enabled;
    private Wrist wrist = Wrist.UNKNOWN;
    private Boolean powered;
    private long eligibleSince = -1;

    public void setEnabled(boolean value, long now) {
        if (enabled != value) eligibleSince = -1;
        enabled = value;
        updateEligibility(now);
    }

    public void setWrist(Wrist value, long now) {
        wrist = value;
        updateEligibility(now);
    }

    public void setPowered(Boolean value, long now) {
        // A dock can look like a wrist. Demand a new reading after undocking.
        if (!Boolean.FALSE.equals(powered) && Boolean.FALSE.equals(value)) wrist = Wrist.UNKNOWN;
        powered = value;
        updateEligibility(now);
    }

    private void updateEligibility(long now) {
        if (!enabled || wrist != Wrist.ON || !Boolean.FALSE.equals(powered)) eligibleSince = -1;
        else if (eligibleSince < 0) eligibleSince = now;
    }

    public Target target(long now, boolean restorePending) {
        if (!enabled) return restorePending ? Target.OFF : Target.NONE;
        // Unknown sensor / charger data never permits airplane mode.
        if (eligibleSince < 0 || now - eligibleSince < CONFIRM_MS) return Target.OFF;
        return Target.ON;
    }

    public long confirmationRemaining(long now) {
        return eligibleSince < 0 ? 0 : Math.max(0, CONFIRM_MS - (now - eligibleSince));
    }

    public Wrist wrist() { return wrist; }
    public Boolean powered() { return powered; }
    public boolean enabled() { return enabled; }
    public String reason(long now) {
        if (!enabled) return "Automation paused";
        if (Boolean.TRUE.equals(powered)) return "On charger · airplane mode off";
        if (powered == null) return "Waiting for charger status";
        if (wrist == Wrist.OFF) return "Off wrist · airplane mode off";
        if (wrist == Wrist.UNKNOWN) return "Waiting for wrist sensor";
        if (confirmationRemaining(now) > 0) return "Confirming watch is on wrist…";
        return "On wrist · airplane mode on";
    }

    public static Wrist reading(float value) {
        // Accuracy is not a confidence score for this binary on-change sensor.
        if (Float.isNaN(value) || Float.isInfinite(value)) return Wrist.UNKNOWN;
        if (value == 0f) return Wrist.OFF;
        if (value == 1f) return Wrist.ON;
        return Wrist.UNKNOWN;
    }

    public static Boolean powerState(int plugged, int batteryStatus) {
        // Charging (2) and full (5) also protect against delayed plugged flags.
        if (plugged > 0 || batteryStatus == 2 || batteryStatus == 5) return true;
        return plugged == 0 ? false : null;
    }
}
