package Kinkin.aeternum.calendar;

import java.util.Locale;

public enum CalendarChannel {
    OVERWORLD,
    NETHER;

    private CalendarChannel() {
    }

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static CalendarChannel fromString(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException var2) {
                return null;
            }
        } else {
            return null;
        }
    }

    // $FF: synthetic method
    private static CalendarChannel[] $values() {
        return new CalendarChannel[]{OVERWORLD, NETHER};
    }
}
