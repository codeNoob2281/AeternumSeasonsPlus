package kinkin.aeternum.heat;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.*;
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

/**
 * 炎热维度环境监听器。
 *
 * <p>定时（每 40 tick）遍历炎热世界 {@code aeternum_heat} 中的玩家：
 * 根据玩家穿戴的“隔热护甲”件数给予抗火效果，或施加着火、虚弱、缓慢与伤害。
 * 隔热护甲通过物品持久化数据键 {@code heat_armor} 识别。</p>
 */
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
            @Override
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

    /**
     * 定时任务入口：为炎热世界中的每个玩家结算高温效果。
     */
    @Override
    public void run() {
        World world = Bukkit.getWorld("aeternum_heat");
        if (world != null) {
            for (Player player : world.getPlayers()) {
                this.applyHeat(player);
            }

        }
    }

    /**
     * 对单个玩家结算高温效果：集齐四件隔热护甲则获得抗火并熄灭火焰，
     * 否则按缺失件数施加递增的伤害、着火与负面状态。
     *
     * @param player 目标玩家
     */
    private void applyHeat(Player player) {
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            if (player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            }

            int pieces = this.countHeatArmorPieces(player);
            if (pieces >= 4) {
                if (player.getFireTicks() > 0) {
                    player.setFireTicks(0);
                }

                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 80, 0, true, false, true));
            } else {
                double damage;
                if (pieces == 3) {
                    damage = (double) 0.5F;
                } else if (pieces == 2) {
                    damage = (double) 1.0F;
                } else if (pieces == 1) {
                    damage = (double) 1.5F;
                } else {
                    damage = (double) 2.0F;
                }

                player.setFireTicks(Math.max(player.getFireTicks(), 60));
                if (player.getHealth() > damage) {
                    player.damage(damage);
                }

                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0, true, true, true));
                World world = player.getWorld();
                world.spawnParticle(Particle.SMOKE, player.getLocation().add((double) 0.0F, (double) 1.0F, (double) 0.0F), 8, 0.4, 0.4, 0.4, 0.01);
            }
        }
    }

    /**
     * 统计玩家身上穿戴的隔热护甲件数（0-4）。
     */
    private int countHeatArmorPieces(Player player) {
        int count = 0;
        if (this.isHeatArmorPiece(player.getInventory().getHelmet())) {
            ++count;
        }

        if (this.isHeatArmorPiece(player.getInventory().getChestplate())) {
            ++count;
        }

        if (this.isHeatArmorPiece(player.getInventory().getLeggings())) {
            ++count;
        }

        if (this.isHeatArmorPiece(player.getInventory().getBoots())) {
            ++count;
        }

        return count;
    }

    /**
     * 判断单个物品是否为隔热护甲（带有 heat_armor 持久化标签）。
     */
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
