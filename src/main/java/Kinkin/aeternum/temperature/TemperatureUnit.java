package kinkin.aeternum.temperature;

/**
 * 温度单位枚举。
 *
 * <p>用于描述温度显示所使用的单位（摄氏度 / 华氏度），并负责配置字符串的解析
 * 以及摄氏度到当前单位的整数换算。显示逻辑依赖本枚举来确定单位与转换结果。</p>
 */
public enum TemperatureUnit {
    CELSIUS,
    FAHRENHEIT;

    TemperatureUnit() {
    }

    /**
     * 根据配置中的原始字符串解析温度单位，无法识别时默认返回摄氏度。
     *
     * <p>接受 {@code F}、{@code FAHRENHEIT}、{@code FAHRENHEITS} 作为华氏度标识，
     * 其余（含 {@code null}）一律视为摄氏度。</p>
     */
    public static TemperatureUnit fromString(String raw) {
        if (raw == null) {
            return CELSIUS;
        } else {
            String normalized = raw.trim().toUpperCase();
            return !"F".equals(normalized) && !"FAHRENHEIT".equals(normalized) && !"FAHRENHEITS".equals(normalized) ? CELSIUS : FAHRENHEIT;
        }
    }

    /**
     * 将摄氏度换算为当前单位的整数值；华氏度按四舍五入计算，摄氏度原样返回。
     */
    public int fromCelsiusRounded(int celsius) {
        return this == FAHRENHEIT ? (int) Math.round((double) celsius * (double) 9.0F / (double) 5.0F + (double) 32.0F) : celsius;
    }

    // $FF: synthetic method
    private static TemperatureUnit[] $values() {
        return new TemperatureUnit[]{CELSIUS, FAHRENHEIT};
    }
}
