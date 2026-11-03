package kinkin.aeternum.calendar;

import java.util.Locale;

/**
 * 日历频道枚举。
 *
 * <p>每个频道代表一套独立推进的日历时间线：{@link #OVERWORLD} 对应主世界，
 * {@link #NETHER} 对应下界。频道决定了世界如何映射到日历，并各自维护独立的
 * 年份、日期与季节状态。</p>
 */
public enum CalendarChannel {
    OVERWORLD,
    NETHER;

    CalendarChannel() {
    }

    /**
     * 返回频道的小写标识（用于配置键、状态文件名等）。
     */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 将字符串解析为对应频道（大小写不敏感）。
     *
     * @param rawName 原始字符串；为 {@code null} 或空白时返回 {@code null}
     * @return 匹配的频道；无法匹配时返回 {@code null}
     */
    public static CalendarChannel fromString(String rawName) {
        if (rawName != null && !rawName.isBlank()) {
            try {
                return valueOf(rawName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
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
