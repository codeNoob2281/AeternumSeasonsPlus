package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.Locale;
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

public final class SnowGolemBiomeProtection implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final boolean enabled;

    public SnowGolemBiomeProtection(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.enabled = plugin.cfg.climate.getBoolean("biome_spoof.protect_snow_golems_from_biome_heat", true);
    }

    public void register() {
        HandlerList.unregisterAll(this);
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(
        priority = EventPriority.LOWEST,
        ignoreCancelled = false
    )
    public void onDamageFirst(EntityDamageEvent event) {
        Entity var3 = event.getEntity();
        if (var3 instanceof Snowman snowman) {
            if (this.isBiomeHeatDamage(event, snowman)) {
                this.blockDamage(event, snowman);
            }
        }
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = false
    )
    public void onDamageFinal(EntityDamageEvent event) {
        Entity var3 = event.getEntity();
        if (var3 instanceof Snowman snowman) {
            if (this.isBiomeHeatDamage(event, snowman)) {
                this.blockDamage(event, snowman);
            }
        }
    }

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

    private void blockDamage(EntityDamageEvent event, Snowman snowman) {
        event.setDamage((double)0.0F);
        event.setCancelled(true);
        if (snowman.getFireTicks() < 0) {
            snowman.setFireTicks(0);
        }

    }

    private boolean isHotBiome(Snowman snowman) {
        String key = this.biomeKey(snowman);
        return key.contains("desert") || key.contains("savanna") || key.contains("badlands");
    }

    private String biomeKey(Snowman snowman) {
        Location location = snowman.getLocation();
        Biome biome = snowman.getWorld().getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return biome.getKey().getKey().toLowerCase(Locale.ROOT);
    }

    private String damageTypeKey(EntityDamageEvent event) {
        try {
            DamageSource source = event.getDamageSource();
            if (source != null && source.getDamageType() != null && source.getDamageType().getKey() != null) {
                return source.getDamageType().getKey().toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable var3) {
        }

        return "unknown";
    }

    private boolean touchesRealHeatSource(Location location) {
        Material feet = location.getBlock().getType();
        Material below = location.clone().subtract((double)0.0F, (double)1.0F, (double)0.0F).getBlock().getType();
        return this.isRealHeatSource(feet) || this.isRealHeatSource(below);
    }

    private boolean isRealHeatSource(Material material) {
        return material == Material.FIRE || material == Material.SOUL_FIRE || material == Material.LAVA || material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE;
    }
}
