package Kinkin.aeternum.calendar;

public final class CalendarState {
    public int year;
    public int day;
    public Season season;
    public boolean monthsEnabled;
    public String monthId;
    public String monthDisplayName;
    public int monthIndex;
    public int daysInMonth;

    public CalendarState(int year, int day, Season season) {
        super();
        this.year = year;
        this.day = day;
        this.season = season;
        this.monthsEnabled = false;
        this.monthId = null;
        this.monthDisplayName = null;
        this.monthIndex = -1;
        this.daysInMonth = 0;
    }

    public CalendarState copy() {
        CalendarState out = new CalendarState(this.year, this.day, this.season);
        out.monthsEnabled = this.monthsEnabled;
        out.monthId = this.monthId;
        out.monthDisplayName = this.monthDisplayName;
        out.monthIndex = this.monthIndex;
        out.daysInMonth = this.daysInMonth;
        return out;
    }
}
