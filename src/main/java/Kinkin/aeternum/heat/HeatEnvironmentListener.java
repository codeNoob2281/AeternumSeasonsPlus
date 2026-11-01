package Kinkin.aeternum.heat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class HeatEnvironmentListener implements Listener, Runnable {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey HEAT_ARMOR_KEY;
    private BukkitTask task;

    public HeatEnvironmentListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.HEAT_ARMOR_KEY = new NamespacedKey(plugin, "heat_armor");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.task = (new BukkitRunnable() {
            public void run() {
                HeatEnvironmentListener.this.run();
            }
        }).runTaskTimer(this.plugin, 40L, 40L);
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        HandlerList.unregisterAll(this);
    }

    public void run() {
        World w = Bukkit.getWorld("aeternum_heat");
        if (w != null) {
            for(Player p : w.getPlayers()) {
                this.applyHeat(p);
            }

        }
    }

    private void applyHeat(Player p) {
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            if (p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            }

            int pieces = this.countHeatArmorPieces(p);
            if (pieces >= 4) {
                if (p.getFireTicks() > 0) {
                    p.setFireTicks(0);
                }

                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 80, 0, true, false, true));
            } else {
                double damage;
                if (pieces == 3) {
                    damage = (double)0.5F;
                } else if (pieces == 2) {
                    damage = (double)1.0F;
                } else if (pieces == 1) {
                    damage = (double)1.5F;
                } else {
                    damage = (double)2.0F;
                }

                p.setFireTicks(Math.max(p.getFireTicks(), 60));
                if (p.getHealth() > damage) {
                    p.damage(damage);
                }

                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, true, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0, true, true, true));
                World w = p.getWorld();
                w.spawnParticle(Particle.SMOKE, p.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 8, 0.4, 0.4, 0.4, 0.01);
            }
        }
    }

    private int countHeatArmorPieces(Player p) {
        int c = 0;
        if (this.isHeatArmorPiece(p.getInventory().getHelmet())) {
            ++c;
        }

        if (this.isHeatArmorPiece(p.getInventory().getChestplate())) {
            ++c;
        }

        if (this.isHeatArmorPiece(p.getInventory().getLeggings())) {
            ++c;
        }

        if (this.isHeatArmorPiece(p.getInventory().getBoots())) {
            ++c;
        }

        return c;
    }

    private boolean isHeatArmorPiece(ItemStack stack) {
        if (stack == null) {
            return false;
        } else {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                return pdc.has(this.HEAT_ARMOR_KEY, PersistentDataType.BYTE);
            }
        }
    }
}
