package Kinkin.aeternum.temperature;

public final class ThermoPlayerState {
    public int displayedTemp = Integer.MIN_VALUE;
    public int actionbarTicks = 0;
    public int heatExposureTicks = 0;
    public int coldExposureTicks = 0;
    public int wetReliefTicks = 0;
    public int hungerCooldownTicks = 0;
    public int heatDamageCooldownTicks = 0;
    public int coldDamageCooldownTicks = 0;
    public int stormSoundCooldownTicks = 0;

    public ThermoPlayerState() {
        super();
    }

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

    public void resetDanger() {
        this.heatExposureTicks = 0;
        this.coldExposureTicks = 0;
    }
}
