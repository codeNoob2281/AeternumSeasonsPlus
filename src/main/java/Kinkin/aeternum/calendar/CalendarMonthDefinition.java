package Kinkin.aeternum.calendar;

public final class CalendarMonthDefinition {
    private final String id;
    private final String displayName;
    private final Season season;
    private final int days;
    private final int realTimeMinutesPerDay;
    private final int daylightMinutes;
    private final int nightMinutes;

    public CalendarMonthDefinition(String id, String displayName, Season season, int days, int realTimeMinutesPerDay, int daylightMinutes, int nightMinutes) {
        super();
        this.id = id;
        this.displayName = displayName;
        this.season = season;
        this.days = Math.max(1, days);
        this.realTimeMinutesPerDay = Math.max(0, realTimeMinutesPerDay);
        this.daylightMinutes = Math.max(0, daylightMinutes);
        this.nightMinutes = Math.max(0, nightMinutes);
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public Season getSeason() {
        return this.season;
    }

    public int getDays() {
        return this.days;
    }

    public int getRealTimeMinutesPerDay() {
        return this.realTimeMinutesPerDay;
    }

    public int getDaylightMinutes() {
        return this.daylightMinutes;
    }

    public int getNightMinutes() {
        return this.nightMinutes;
    }
}
