package dev.wristflight;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs as shell inside Shizuku. Exposes only airplane-mode operations. */
public final class FlightBridgeService extends IFlightBridge.Stub {
    public FlightBridgeService() {}

    private String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            // These fixed commands emit at most a few lines; no arbitrary shell input.
            if (!process.waitFor(4, TimeUnit.SECONDS)) throw new IllegalStateException("System command timed out");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 8192) throw new IllegalStateException("Unexpected system output");
            }
            String output = bytes.toString(StandardCharsets.UTF_8.name()).trim();
            if (process.exitValue() != 0) throw new IllegalStateException("Android rejected command: " + output);
            return output;
        } finally { process.destroy(); }
    }

    @Override public synchronized String probe() {
        try {
            String value = command("/system/bin/cmd", "connectivity", "airplane-mode");
            return value.equals("enabled") || value.equals("disabled") ? "OK: " + value : "Unsupported command: " + value;
        } catch (Exception e) { return "Unavailable: " + e.getMessage(); }
    }

    @Override public synchronized String setAirplaneMode(boolean enabled) {
        try {
            command("/system/bin/cmd", "connectivity", "airplane-mode", enabled ? "enable" : "disable");
            String value = command("/system/bin/cmd", "connectivity", "airplane-mode");
            if (value.equals(enabled ? "enabled" : "disabled")) return "OK";
            return "Android did not confirm airplane mode " + (enabled ? "on" : "off");
        } catch (Exception e) { return "Unavailable: " + e.getMessage(); }
    }

    @Override public void destroy() { System.exit(0); }
}
