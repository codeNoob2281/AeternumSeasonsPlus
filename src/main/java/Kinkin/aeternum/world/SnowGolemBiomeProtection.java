package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.Locale;

/**
 * 雪傀儡生物群系高温保护。
 *
 * <p>原版中身处沙漠、热带草原、恶地等炎热生物群系的雪傀儡会因“融化”或“着火”类伤害持续受伤。
 * 本监听器通过拦截 {@link EntityDamageEvent}，识别出由生物群系高温引起的伤害（而非真实火源/岩浆），
 * 将其取消并清零，从而在配置开启时保护雪傀儡。</p>
 *
 * <p>相关开关：{@code biome_spoof.protect_snow_golems_from_biome_heat}。
 * 仅在主世界（NORMAL）生效，且若雪傀儡接触到真实火源（火、岩浆、营火等）则不予保护。</p>
 */
public final class SnowGolemBiomeProtection implements Listener {
    private final AeternumSeasonsPlugin plugin;
    /**
     * 是否启用保护（来自配置）。
     */
    private final boolean enabled;

    public SnowGolemBiomeProtection(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.enabled = plugin.cfg.climate.getBoolean("biome_spoof.protect_snow_golems_from_biome_heat", true);
    }

    /**
     * 注册监听器（先注销旧实例以避免重复注册）。
     */
    public void register() {
        HandlerList.unregisterAll(this);
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 注销监听器。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 在 LOWEST 优先级提前拦截生物群系高温伤害。
     */
    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onDamageFirst(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Snowman snowman) {
            if (this.isBiomeHeatDamage(event, snowman)) {
                this.blockDamage(event, snowman);
            }
        }
    }

    /**
     * 在 MONITOR 优先级兜底拦截（其他插件可能重新设置伤害）。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = false
    )
    public void onDamageFinal(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Snowman snowman) {
            if (this.isBiomeHeatDamage(event, snowman)) {
                this.blockDamage(event, snowman);
            }
        }
    }

    /**
     * 判断该伤害是否属于需要保护屏蔽的生物群系高温伤害。
     */
    private boolean isBiomeHeatDamage(EntityDamageEvent event, Snowman snowman) {
        if (!this.enabled) {
            return false;
        } else if (snowman.getWorld().getEnvironment() != Environment.NORMAL) {
            return false;
        } else if (event.getCause() == DamageCause.MELTING) {
            return true;
        } else {
            String damageType = this.damageTypeKey(event);
            if (damageType.endsWith("melting")) {
                return true;
            } else {
                boolean onFireDamage = event.getCause() == DamageCause.FIRE_TICK || event.getCause() == DamageCause.FIRE || damageType.endsWith("on_fire") || damageType.endsWith("in_fire");
                return onFireDamage && this.isHotBiome(snowman) && snowman.getFireTicks() <= 0 && !this.touchesRealHeatSource(snowman.getLocation());
            }
        }
    }

    /**
     * 取消伤害并将伤害值与火焰刻清零。
     */
    private void blockDamage(EntityDamageEvent event, Snowman snowman) {
        event.setDamage((double) 0.0F);
        event.setCancelled(true);
        if (snowman.getFireTicks() < 0) {
            snowman.setFireTicks(0);
        }

    }

    /**
     * 判断雪傀儡所处生物群系是否为炎热类型。
     */
    private boolean isHotBiome(Snowman snowman) {
        String key = this.biomeKey(snowman);
        return key.contains("desert") || key.contains("savanna") || key.contains("badlands");
    }

    /**
     * 获取雪傀儡所在生物群系的完整小写 key。
     */
    private String biomeKey(Snowman snowman) {
        Location location = snowman.getLocation();
        Biome biome = snowman.getWorld().getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return biome.getKey().getKey().toLowerCase(Locale.ROOT);
    }

    /**
     * 获取伤害类型的小写 key，获取失败时返回 "unknown"。
     */
    private String damageTypeKey(EntityDamageEvent event) {
        try {
            DamageSource source = event.getDamageSource();
            if (source != null && source.getDamageType() != null && source.getDamageType().getKey() != null) {
                return source.getDamageType().getKey().toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }

        return "unknown";
    }

    /**
     * 判断玩家脚下与脚下一格是否为真实火源。
     */
    private boolean touchesRealHeatSource(Location location) {
        Material feet = location.getBlock().getType();
        Material below = location.clone().subtract((double) 0.0F, (double) 1.0F, (double) 0.0F).getBlock().getType();
        return this.isRealHeatSource(feet) || this.isRealHeatSource(below);
    }

    /**
     * 判断方块类型是否为真实火源（火、岩浆、营火等）。
     */
    private boolean isRealHeatSource(Material material) {
        return material == Material.FIRE || material == Material.SOUL_FIRE || material == Material.LAVA || material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE;
    }
}
