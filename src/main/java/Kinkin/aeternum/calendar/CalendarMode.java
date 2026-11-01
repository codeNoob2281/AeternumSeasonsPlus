package Kinkin.aeternum.calendar;

import java.util.Locale;

public enum CalendarMode {
    SEASONS,
    MONTHS;

    private CalendarMode() {
    }

    public static CalendarMode fromString(String raw, CalendarMode def) {
        if (raw != null && !raw.isBlank()) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException var3) {
                return def;
            }
        } else {
            return def;
        }
    }

    // $FF: synthetic method
    private static CalendarMode[] $values() {
        return new CalendarMode[]{SEASONS, MONTHS};
    }
}
