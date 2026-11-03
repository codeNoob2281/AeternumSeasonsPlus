package kinkin.aeternum.temperature;

/**
 * 单个玩家的温度状态缓存。
 *
 * <p>记录当前展示的温度值以及若干倒计时字段（动作栏显示、涉水缓解、饥饿/伤害冷却、
 * 风暴音效冷却等）。由 {@link ThermoService} 按检查周期创建并递减，用于跨 tick
 * 保持玩家的温度相关状态。</p>
 */
public final class ThermoPlayerState {
    /**
     * 当前动作栏展示的温度；{@link Integer#MIN_VALUE} 表示尚未初始化。
     */
    public int displayedTemp = Integer.MIN_VALUE;
    /**
     * 动作栏剩余显示时长（tick）。
     */
    public int actionbarTicks = 0;
    /**
     * 已持续受热暴露的时间（tick）。
     */
    public int heatExposureTicks = 0;
    /**
     * 已持续受冷暴露的时间（tick）。
     */
    public int coldExposureTicks = 0;
    /**
     * 涉水/饮水后的缓解剩余时长（tick）。
     */
    public int wetReliefTicks = 0;
    /**
     * 饥饿掉落的冷却剩余时长（tick）。
     */
    public int hungerCooldownTicks = 0;
    /**
     * 受热伤害的冷却剩余时长（tick）。
     */
    public int heatDamageCooldownTicks = 0;
    /**
     * 受冷伤害的冷却剩余时长（tick）。
     */
    public int coldDamageCooldownTicks = 0;
    /**
     * 沙暴音效的冷却剩余时长（tick）。
     */
    public int stormSoundCooldownTicks = 0;

    public ThermoPlayerState() {
        super();
    }

    /**
     * 将各个倒计时字段统一递减指定量，且不会低于 0。
     */
    public void tickDown(int amount) {
        if (this.actionbarTicks > 0) {
            this.actionbarTicks = Math.max(0, this.actionbarTicks - amount);
        }

        if (this.wetReliefTicks > 0) {
            this.wetReliefTicks = Math.max(0, this.wetReliefTicks - amount);
        }

        if (this.hungerCooldownTicks > 0) {
            this.hungerCooldownTicks = Math.max(0, this.hungerCooldownTicks - amount);
        }

        if (this.heatDamageCooldownTicks > 0) {
            this.heatDamageCooldownTicks = Math.max(0, this.heatDamageCooldownTicks - amount);
        }

        if (this.coldDamageCooldownTicks > 0) {
            this.coldDamageCooldownTicks = Math.max(0, this.coldDamageCooldownTicks - amount);
        }

        if (this.stormSoundCooldownTicks > 0) {
            this.stormSoundCooldownTicks = Math.max(0, this.stormSoundCooldownTicks - amount);
        }

    }

    /**
     * 清空冷热暴露计时，通常用于玩家进入安全环境或创造/旁观模式时。
     */
    public void resetDanger() {
        this.heatExposureTicks = 0;
        this.coldExposureTicks = 0;
    }
}
