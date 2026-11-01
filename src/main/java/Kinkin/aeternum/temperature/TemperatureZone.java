package Kinkin.aeternum.temperature;

public enum TemperatureZone {
    NORMAL,
    HOT,
    SNOWY,
    FROZEN;

    private TemperatureZone() {
    }

    // $FF: synthetic method
    private static TemperatureZone[] $values() {
        return new TemperatureZone[]{NORMAL, HOT, SNOWY, FROZEN};
    }
}
