package dev.wristflight;

interface IFlightBridge {
    String probe() = 1;
    String setAirplaneMode(boolean enabled) = 2;
    void destroy() = 16777114;
}
