package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 永霜世界环境监听器与循环任务。
 *
 * <p>实现 {@link Runnable}，由定时任务每 2 秒对 {@code aeternum_frost} 世界中的所有玩家执行：
 * 生成口中呼出的寒气粒子，并根据是否暴露在户外、是否穿戴足够护甲、手持/附近是否有热源、
 * 以及是否处于暴风雪等因素施加冻结、减速/挖掘疲劳乃至失明与少量伤害；在室内或有保暖条件时
 * 则逐步解冻。依赖 {@link AeternumSeasonsPlugin} 完成事件注册与任务调度。</p>
 */
public final class FrostEnvironmentListener implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private BukkitTask task;
    private static final String FROST_WORLD_NAME = "aeternum_frost";

    public FrostEnvironmentListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.task = (new BukkitRunnable() {
            public void run() {
                FrostEnvironmentListener.this.run();
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
     * 每周期遍历永霜世界的在线玩家，生成寒气并为每人结算寒冷效果。
     */
    public void run() {
        World world = Bukkit.getWorld("aeternum_frost");
        if (world != null) {
            boolean storm = world.hasStorm();

            for(Player player : world.getPlayers()) {
                this.spawnColdBreath(player);
                this.applyCold(player, storm);
            }

        }
    }

    /**
     * 结算单个玩家的寒冷状态：户外且缺乏保暖时施加冻结、减速、挖掘疲劳，
     * 暴风雪下再叠加失明与少量伤害；否则逐步解冻。
     */
    private void applyCold(Player player, boolean storm) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world != null) {
            boolean outside = this.isOutside(loc);
            if (!outside) {
                if (player.getFreezeTicks() > 0) {
                    player.setFreezeTicks(Math.max(0, player.getFreezeTicks() - 20));
                }

            } else {
                int armorPieces = this.countArmorPieces(player);
                boolean hasHotItem = this.isHotItem(player.getInventory().getItemInMainHand().getType()) || this.isHotItem(player.getInventory().getItemInOffHand().getType());
                boolean nearHeat = this.isNearHeatSource(loc, 3);
                long time = world.getTime();
                boolean isNight = time >= 13000L && time < 23000L;
                int requiredArmorPieces = isNight ? 4 : 2;
                boolean coldProtected = armorPieces >= requiredArmorPieces || hasHotItem || nearHeat;
                if (!coldProtected) {
                    int freezeStep = storm ? 80 : 40;
                    player.setFreezeTicks(Math.min(player.getFreezeTicks() + freezeStep, 200));
                    int slowAmp = storm ? 1 : 0;
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, slowAmp, true, true, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, slowAmp, true, true, true));
                    if (storm && !hasHotItem) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, true, true));
                    }

                    if (storm && player.getHealth() > (double)1.0F) {
                        player.damage((double)1.0F);
                    }
                } else {
                    if (player.getFreezeTicks() > 0) {
                        player.setFreezeTicks(Math.max(0, player.getFreezeTicks() - 20));
                    }

                    if (storm && !hasHotItem) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, true, true));
                    }
                }

            }
        }
    }

    /**
     * 判断玩家是否处于户外（其脚部 y 不低于该列地表高度减一）。
     */
    private boolean isOutside(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            int surfaceY = world.getHighestBlockYAt(x, z);
            return loc.getBlockY() >= surfaceY - 1;
        }
    }

    /**
     * 统计玩家当前穿戴的护甲件数。
     */
    private int countArmorPieces(Player player) {
        int count = 0;
        if (player.getInventory().getHelmet() != null) {
            ++count;
        }

        if (player.getInventory().getChestplate() != null) {
            ++count;
        }

        if (player.getInventory().getLeggings() != null) {
            ++count;
        }

        if (player.getInventory().getBoots() != null) {
            ++count;
        }

        return count;
    }

    /**
     * 判断手持物品是否属于可提供保暖的“热源”物品。
     */
    private boolean isHotItem(Material material) {
        boolean isHot;
        switch (material) {
            case TORCH:
            case SOUL_TORCH:
            case REDSTONE_TORCH:
            case LANTERN:
            case SOUL_LANTERN:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case MAGMA_BLOCK:
            case LAVA_BUCKET:
            case FIRE_CHARGE:
            case BLAZE_ROD:
                isHot = true;
                break;
            default:
                isHot = false;
        }

        return isHot;
    }

    /**
     * 在给定半径内扫描是否存在热源方块。
     */
    private boolean isNearHeatSource(Location loc, int radius) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            int baseX = loc.getBlockX();
            int baseY = loc.getBlockY();
            int baseZ = loc.getBlockZ();

            for(int x = baseX - radius; x <= baseX + radius; ++x) {
                for(int y = baseY - radius; y <= baseY + radius; ++y) {
                    for(int z = baseZ - radius; z <= baseZ + radius; ++z) {
                        Block block = world.getBlockAt(x, y, z);
                        if (this.isHotBlock(block.getType())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    /**
     * 判断方块是否为可产生热量的方块（火把、营火、岩浆、熔炉等）。
     */
    private boolean isHotBlock(Material material) {
        boolean isHot;
        switch (material) {
            case TORCH:
            case SOUL_TORCH:
            case REDSTONE_TORCH:
            case LANTERN:
            case SOUL_LANTERN:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case MAGMA_BLOCK:
            case FIRE:
            case SOUL_FIRE:
            case LAVA:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
                isHot = true;
                break;
            case LAVA_BUCKET:
            case FIRE_CHARGE:
            case BLAZE_ROD:
            default:
                isHot = false;
        }

        return isHot;
    }

    /**
     * 在玩家视线前方生成一小段寒气雪花粒子。
     */
    private void spawnColdBreath(Player player) {
        Location eye = player.getEyeLocation().clone();
        World world = eye.getWorld();
        if (world != null) {
            Vector dir = eye.getDirection().normalize().multiply((double)0.25F);
            Location pos = eye.clone();

            for(int i = 0; i < 6; ++i) {
                pos.add(dir);
                world.spawnParticle(Particle.SNOWFLAKE, pos.getX(), pos.getY(), pos.getZ(), 1, 0.03, 0.03, 0.03, (double)0.0F);
            }

        }
    }
}
