package Kinkin.aeternum.temperature;

public enum TemperatureUnit {
    CELSIUS,
    FAHRENHEIT;

    private TemperatureUnit() {
    }

    public static TemperatureUnit fromString(String raw) {
        if (raw == null) {
            return CELSIUS;
        } else {
            String v = raw.trim().toUpperCase();
            return !v.equals("F") && !v.equals("FAHRENHEIT") && !v.equals("FAHRENHEITS") ? CELSIUS : FAHRENHEIT;
        }
    }

    public int fromCelsiusRounded(int celsius) {
        return this == FAHRENHEIT ? (int)Math.round((double)celsius * (double)9.0F / (double)5.0F + (double)32.0F) : celsius;
    }

    // $FF: synthetic method
    private static TemperatureUnit[] $values() {
        return new TemperatureUnit[]{CELSIUS, FAHRENHEIT};
    }
}
