package Kinkin.aeternum.calendar;

/**
 * 日历的运行时状态快照。
 *
 * <p>保存某个频道当前的时间位置：年、日、季节；当启用月份模式时，额外记录当前月份的
 * 标识、显示名、序号与天数。字段均为 public，由 {@link SeasonService} 直接读写；
 * 对外通常通过 {@link #copy()} 返回副本，以免外部修改内部状态。</p>
 */
public final class CalendarState {
    /** 当前年份（从 1 开始）。 */
    public int year;
    /** 当前在当前周期（季节或月份）内的第几天（从 1 开始）。 */
    public int day;
    /** 当前季节。 */
    public Season season;
    /** 是否启用了月份模式。 */
    public boolean monthsEnabled;
    /** 当前月份标识；未启用月份模式时为 null。 */
    public String monthId;
    /** 当前月份显示名；未启用月份模式时为 null。 */
    public String monthDisplayName;
    /** 当前月份在月份列表中的索引；未启用月份模式时为 -1。 */
    public int monthIndex;
    /** 当前月份的天数；未启用月份模式时为 0。 */
    public int daysInMonth;

    /** 构造一个仅含基础字段、尚未启用月份模式的日历状态。 */
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

    /** 返回一个新的深拷贝，修改副本不会影响原状态。 */
    public CalendarState copy() {
        CalendarState copy = new CalendarState(this.year, this.day, this.season);
        copy.monthsEnabled = this.monthsEnabled;
        copy.monthId = this.monthId;
        copy.monthDisplayName = this.monthDisplayName;
        copy.monthIndex = this.monthIndex;
        copy.daysInMonth = this.daysInMonth;
        return copy;
    }
}
