package dev.wristflight;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.wristflight.FlightPolicy.Target.*;
import dev.wristflight.FlightPolicy.Wrist;
import static dev.wristflight.FlightPolicy.Wrist.UNKNOWN;

public class FlightPolicyTest {
    private FlightPolicy monitoring() {
        FlightPolicy policy = new FlightPolicy();
        policy.setPowered(false, 0);
        policy.setEnabled(true, 0);
        return policy;
    }

    @Test public void defaultNeverChangesAnything() {
        assertEquals(NONE, new FlightPolicy().target(100_000, false));
    }
    @Test public void pendingRecoveryWorksEvenWithAutomationDisabled() {
        assertEquals(OFF, new FlightPolicy().target(100_000, true));
    }
    @Test public void startingNeedsAnActualWristReading() {
        assertEquals(OFF, monitoring().target(100_000, false));
    }
    @Test public void wornForThreeSecondsEnables() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 100);
        assertEquals(OFF, policy.target(3099, false));
        assertEquals(ON, policy.target(3100, false));
    }
    @Test public void chargingOverridesWornEvenWhenFullyCharged() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 100);
        assertEquals(ON, policy.target(4000, false));
        policy.setPowered(FlightPolicy.powerState(4, 5), 4001);
        assertEquals(OFF, policy.target(4001, false));
    }
    @Test public void removalDoesNotWaitForDebounce() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setWrist(Wrist.OFF, 4000);
        assertEquals(OFF, policy.target(4000, true));
    }
    @Test public void briefContactNeverEnables() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setWrist(Wrist.OFF, 2000);
        assertEquals(OFF, policy.target(8000, false));
    }
    @Test public void repeatedOnEventsDoNotRestartConfirmation() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 100);
        policy.setWrist(Wrist.ON, 1000);
        policy.setWrist(Wrist.ON, 2000);
        assertEquals(ON, policy.target(3100, false));
    }
    @Test public void newWearAfterRemovalNeedsFullConfirmation() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setWrist(Wrist.OFF, 2999);
        policy.setWrist(Wrist.ON, 3000);
        assertEquals(OFF, policy.target(5999, false));
        assertEquals(ON, policy.target(6000, false));
    }
    @Test public void undockingDiscardsTheReadingTakenOnTheDock() {
        FlightPolicy policy = monitoring();
        policy.setPowered(true, 10);
        policy.setWrist(Wrist.ON, 20);
        policy.setPowered(false, 10_000);
        assertEquals(UNKNOWN, policy.wrist());
        assertEquals(OFF, policy.target(100_000, false));
        policy.setWrist(Wrist.ON, 101_000);
        assertEquals(OFF, policy.target(103_999, false));
        assertEquals(ON, policy.target(104_000, false));
    }
    @Test public void repeatedUnpluggedBatteryUpdatesDoNotResetWrist() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setPowered(false, 1500);
        assertEquals(ON, policy.target(3001, false));
    }
    @Test public void missingBatteryDataPreventsEntry() {
        FlightPolicy policy = new FlightPolicy();
        policy.setEnabled(true, 0);
        policy.setWrist(Wrist.ON, 1);
        assertEquals(OFF, policy.target(100_000, false));
    }
    @Test public void sensorLossRestoresAndResetsConfirmation() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        assertEquals(ON, policy.target(4000, true));
        policy.setWrist(UNKNOWN, 4001);
        assertEquals(OFF, policy.target(4001, true));
        policy.setWrist(Wrist.ON, 5000);
        assertEquals(OFF, policy.target(5001, true));
    }
    @Test public void pauseDuringEnableDemandsOffOnCompletion() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        assertEquals(ON, policy.target(4000, true));
        policy.setEnabled(false, 4001);
        assertEquals(OFF, policy.target(4002, true));
        assertEquals(NONE, policy.target(4003, false));
    }
    @Test public void chargingDuringEnableDemandsOffOnCompletion() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        assertEquals(ON, policy.target(4000, true));
        policy.setPowered(true, 4001);
        assertEquals(OFF, policy.target(4002, true));
    }
    @Test public void pausingAndResumingDoesNotReuseOldConfirmation() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setEnabled(false, 5000);
        policy.setEnabled(true, 6000);
        assertEquals(OFF, policy.target(6001, false));
    }
    @Test public void repeatedResumeDoesNotInterruptActiveMonitoring() {
        FlightPolicy policy = monitoring();
        policy.setWrist(Wrist.ON, 1);
        policy.setEnabled(true, 5000);
        assertEquals(ON, policy.target(5000, true));
    }
    @Test public void invalidSensorReadingsAreNeverTreatedAsWorn() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1, 0.5f, 2}) {
            assertEquals(UNKNOWN, FlightPolicy.reading(invalid));
        }
        assertEquals(Wrist.OFF, FlightPolicy.reading(0));
        assertEquals(Wrist.ON, FlightPolicy.reading(1));
    }
    @Test public void chargerDetectionIncludesWirelessFullAndTemporarilyNotCharging() {
        assertEquals(Boolean.TRUE, FlightPolicy.powerState(4, 2));
        assertEquals(Boolean.TRUE, FlightPolicy.powerState(4, 5));
        assertEquals(Boolean.TRUE, FlightPolicy.powerState(4, 4));
        assertEquals(Boolean.TRUE, FlightPolicy.powerState(0, 2));
        assertEquals(Boolean.TRUE, FlightPolicy.powerState(0, 5));
        assertEquals(Boolean.FALSE, FlightPolicy.powerState(0, 3));
        assertNull(FlightPolicy.powerState(-1, 1));
    }
    @Test public void completeStateMatrixNeverEnablesUnlessWornAndUnplugged() {
        for (FlightPolicy.Wrist wrist : FlightPolicy.Wrist.values()) {
            for (Boolean powered : new Boolean[]{null, true, false}) {
                FlightPolicy policy = new FlightPolicy();
                policy.setEnabled(true, 0);
                policy.setPowered(powered, 1);
                policy.setWrist(wrist, 2);
                assertEquals(wrist == Wrist.ON && Boolean.FALSE.equals(powered) ? ON : OFF, policy.target(10_000, true));
            }
        }
    }
}
