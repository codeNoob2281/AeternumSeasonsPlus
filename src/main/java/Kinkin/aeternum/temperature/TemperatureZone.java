package Kinkin.aeternum.temperature;

/**
 * 温度区域枚举。
 *
 * <p>将玩家所在的生物群系归纳为四档温区：{@link #NORMAL 普通}、{@link #HOT 炎热}、
 * {@link #SNOWY 寒冷}、{@link #FROZEN 冰冻}。温度计算与冷热伤害判定均以该温区为
 * 基础进行分支。</p>
 */
public enum TemperatureZone {
    NORMAL,
    HOT,
    SNOWY,
    FROZEN;

    private TemperatureZone() {
    }

    // $FF: synthetic method
    private static TemperatureZone[] $values() {
        return new TemperatureZone[]{NORMAL, HOT, SNOWY, FROZEN};
    }
}
