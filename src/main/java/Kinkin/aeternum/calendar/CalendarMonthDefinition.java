package Kinkin.aeternum.calendar;

/**
 * 单个日历月份的定义（不可变）。
 *
 * <p>描述月份在配置中的静态属性：标识、显示名、所属季节、天数，以及该月每天对应的
 * 现实时长与昼/夜分钟数。构造函数会对数值做下限保护——天数至少为 1，其余时长不为负。</p>
 */
public final class CalendarMonthDefinition {
    private final String id;
    private final String displayName;
    private final Season season;
    private final int days;
    private final int realTimeMinutesPerDay;
    private final int daylightMinutes;
    private final int nightMinutes;

    /**
     * 创建一个月份定义。
     *
     * @param id                    月份标识（配置键）
     * @param displayName           展示名称
     * @param season                该月所属季节
     * @param days                  该月天数（最小为 1）
     * @param realTimeMinutesPerDay 每天对应的现实分钟数（最小为 0）
     * @param daylightMinutes       白天现实分钟数（最小为 0）
     * @param nightMinutes          夜晚现实分钟数（最小为 0）
     */
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
