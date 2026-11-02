package Kinkin.aeternum.calendar;

import java.util.Locale;

/**
 * 日历模式枚举。
 *
 * <p>决定日历按哪种周期推进：{@link #SEASONS} 以季节为周期（每季若干天），
 * {@link #MONTHS} 以月份为周期（每月若干天，并可绑定季节）。</p>
 */
public enum CalendarMode {
    SEASONS,
    MONTHS;

    private CalendarMode() {
    }

    /**
     * 将字符串解析为对应模式（大小写不敏感）。
     *
     * @param rawName     原始字符串
     * @param defaultMode 解析失败或输入为空时使用的默认模式
     * @return 匹配的模式；无法匹配时返回 {@code defaultMode}
     */
    public static CalendarMode fromString(String rawName, CalendarMode defaultMode) {
        if (rawName != null && !rawName.isBlank()) {
            try {
                return valueOf(rawName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return defaultMode;
            }
        } else {
            return defaultMode;
        }
    }

    // $FF: synthetic method
    private static CalendarMode[] $values() {
        return new CalendarMode[]{SEASONS, MONTHS};
    }
}
