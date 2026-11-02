package Kinkin.aeternum.calendar;

/**
 * 季节枚举。
 *
 * <p>定义一年中的四个季节，并提供面向玩家展示的西班牙语名称。</p>
 */
public enum Season {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER;

    private Season() {
    }

    /** 返回该季节的展示名称（西班牙语）。 */
    public String display() {
        String displayName;
        switch (this.ordinal()) {
            case 0 -> displayName = "Primavera";
            case 1 -> displayName = "Verano";
            case 2 -> displayName = "Otoño";
            case 3 -> displayName = "Invierno";
            default -> throw new MatchException((String)null, (Throwable)null);
        }

        return displayName;
    }

    // $FF: synthetic method
    private static Season[] $values() {
        return new Season[]{SPRING, SUMMER, AUTUMN, WINTER};
    }
}
