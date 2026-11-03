package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerFishEvent.State;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 下界钓鱼大赛事件（Nether Fishing Derby）。
 *
 * <p>当有玩家身处下界时，有一定概率触发本事件。事件期间，玩家在下界岩浆中钓鱼：
 * <ul>
 *     <li>鱼钩会自动停留在岩浆表面；</li>
 *     <li>抛竿后经过随机时间出现“咬钩”提示与特效；</li>
 *     <li>在咬钩时间窗口内收竿可获得随机的岩浆战利品，失败则只提示无掉落。</li>
 * </ul>
 * 通过多个 {@link BukkitTask} 管理每位玩家的漂浮与咬钩计时，事件结束时统一取消。</p>
 */
public final class NetherFishingDerbyEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 事件最短持续天数。
     */
    private int minDur;
    /**
     * 事件最长持续天数。
     */
    private int maxDur;
    /**
     * 每天触发事件的基础概率。
     */
    private double baseChance;
    /**
     * 判定有效垂钓所需的最少岩浆中停留秒数。
     */
    private int minLavaSeconds;
    /**
     * 两次有效奖励之间的冷却时间（毫秒）。
     */
    private long cooldownMs;
    /**
     * 咬钩后给予奖励的概率。
     */
    private double rewardChance;
    /**
     * 咬钩等待的最短秒数。
     */
    private int biteMinSeconds;
    /**
     * 咬钩等待的最长秒数。
     */
    private int biteMaxSeconds;
    /**
     * 咬钩后允许收竿的时间窗口（毫秒）。
     */
    private long biteWindowMs;
    /**
     * 是否让鱼钩停留在岩浆表面。
     */
    private boolean keepHookOnSurface;
    /**
     * 记录每位玩家最近一次抛竿时间。
     */
    private final Map<UUID, Long> castAt = new HashMap();
    /**
     * 记录每位玩家最近一次获得奖励的时间。
     */
    private final Map<UUID, Long> lastRewardAt = new HashMap();
    /**
     * 记录每位玩家当前鱼钩的 UUID。
     */
    private final Map<UUID, UUID> hookId = new HashMap();
    /**
     * 记录每位玩家咬钩就绪的时间。
     */
    private final Map<UUID, Long> biteReadyAt = new HashMap();
    /**
     * 记录每位玩家咬钩计时任务。
     */
    private final Map<UUID, BukkitTask> biteTasks = new HashMap();
    /**
     * 记录每位玩家鱼钩漂浮维护任务。
     */
    private final Map<UUID, BukkitTask> floatTasks = new HashMap();

    public NetherFishingDerbyEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.nether_fishing_derby.enabled", true);
        this.minDur = config.getInt("events.nether_fishing_derby.min_duration_days", 1);
        this.maxDur = config.getInt("events.nether_fishing_derby.max_duration_days", 1);
        this.baseChance = config.getDouble("events.nether_fishing_derby.base_chance_per_day", 0.08);
        this.minLavaSeconds = config.getInt("events.nether_fishing_derby.min_lava_seconds", 2);
        this.cooldownMs = config.getLong("events.nether_fishing_derby.cooldown_ms", 1200L);
        this.rewardChance = config.getDouble("events.nether_fishing_derby.reward_chance", 0.85);
        this.biteMinSeconds = config.getInt("events.nether_fishing_derby.bite_min_seconds", 2);
        this.biteMaxSeconds = config.getInt("events.nether_fishing_derby.bite_max_seconds", 6);
        this.biteWindowMs = config.getLong("events.nether_fishing_derby.bite_window_ms", 4500L);
        this.keepHookOnSurface = config.getBoolean("events.nether_fishing_derby.keep_hook_on_surface", true);
    }

    @Override
    public String getId() {
        return "nether_fishing_derby";
    }

    @Override
    public String getDisplayName() {
        return "Nether Fishing Derby";
    }

    @Override
    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    @Override
    public int getMinDurationDays() {
        return this.minDur;
    }

    @Override
    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /**
     * 仅当有玩家位于下界、且通过基础概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((player) -> player.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始时向下界玩家发送标题提示。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.nether_fishing_derby.title"), this.plugin.lang.tr(player, "event.nether_fishing_derby.subtitle"), 20, 80, 40);
            }
        }

    }

    /**
     * 事件结束时取消所有计时任务、清空记录，并向下界玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.biteTasks.values().forEach(BukkitTask::cancel);
        this.floatTasks.values().forEach(BukkitTask::cancel);
        this.biteTasks.clear();
        this.floatTasks.clear();
        this.castAt.clear();
        this.lastRewardAt.clear();
        this.hookId.clear();
        this.biteReadyAt.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.nether_fishing_derby.end"));
            }
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 向玩家发送动作栏消息。
     */
    private void actionbar(Player player, String msg) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
    }

    /**
     * 取消并移除某个玩家的咬钩与漂浮任务。
     */
    private void cancelTasks(UUID pid) {
        BukkitTask biteTask = (BukkitTask) this.biteTasks.remove(pid);
        if (biteTask != null) {
            biteTask.cancel();
        }

        BukkitTask floatTask = (BukkitTask) this.floatTasks.remove(pid);
        if (floatTask != null) {
            floatTask.cancel();
        }

    }

    /**
     * 根据 UUID 获取存活的鱼钩实体，找不到则返回 null。
     */
    private FishHook getHook(UUID hookUuid) {
        if (hookUuid == null) {
            return null;
        } else {
            Entity entity = Bukkit.getEntity(hookUuid);
            if (entity instanceof FishHook) {
                FishHook fishHook = (FishHook) entity;
                if (!fishHook.isDead()) {
                    return fishHook;
                }
            }

            return null;
        }
    }

    /**
     * 监听钓鱼事件：管理抛竿、咬钩计时与收竿结算的完整流程。
     */
    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() == Environment.NETHER) {
            FishHook hook = event.getHook();
            if (hook != null) {
                UUID pid = player.getUniqueId();
                PlayerFishEvent.State state = event.getState();
                if (state == State.FISHING) {
                    this.castAt.put(pid, System.currentTimeMillis());
                    this.hookId.put(pid, hook.getUniqueId());
                    this.biteReadyAt.remove(pid);
                    this.cancelTasks(pid);
                    if (this.keepHookOnSurface) {
                        BukkitTask floatTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                            if (player.isOnline() && player.getWorld().getEnvironment() == Environment.NETHER) {
                                FishHook fishHook = this.getHook((UUID) this.hookId.get(pid));
                                if (fishHook != null) {
                                    if (fishHook.getLocation().getBlock().getType() == Material.LAVA) {
                                        Location hookLocation = fishHook.getLocation();
                                        double surfaceY = (double) hookLocation.getBlockY() + 0.12;
                                        if (hookLocation.getY() < surfaceY) {
                                            Location newLocation = hookLocation.clone();
                                            newLocation.setY(surfaceY);
                                            fishHook.teleport(newLocation);
                                            fishHook.setVelocity(new Vector(0, 0, 0));
                                        }

                                    }
                                }
                            } else {
                                this.cancelTasks(pid);
                            }
                        }, 1L, 5L);
                        this.floatTasks.put(pid, floatTask);
                    }

                    int delaySec = ThreadLocalRandom.current().nextInt(Math.max(1, this.biteMinSeconds), Math.max(this.biteMinSeconds + 1, this.biteMaxSeconds + 1));
                    BukkitTask biteTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                        if (player.isOnline() && player.getWorld().getEnvironment() == Environment.NETHER) {
                            FishHook fishHook = this.getHook((UUID) this.hookId.get(pid));
                            if (fishHook != null) {
                                if (fishHook.getLocation().getBlock().getType() == Material.LAVA) {
                                    this.biteReadyAt.put(pid, System.currentTimeMillis());
                                    Location effectLocation = fishHook.getLocation().clone().add((double) 0.0F, (double) 0.75F, (double) 0.0F);
                                    World world = effectLocation.getWorld();
                                    world.playSound(effectLocation, Sound.ENTITY_BLAZE_SHOOT, 1.2F, 0.7F);
                                    world.spawnParticle(Particle.LAVA, effectLocation, 26, (double) 0.25F, 0.08, (double) 0.25F, 0.01);
                                    world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, effectLocation, 10, 0.18, 0.05, 0.18, 0.01);
                                    world.spawnParticle(Particle.ASH, effectLocation, 14, (double) 0.25F, 0.06, (double) 0.25F, 0.01);
                                    world.spawnParticle(Particle.FLAME, effectLocation, 10, 0.2, 0.05, 0.2, 0.01);
                                    this.actionbar(player, this.plugin.lang.tr(player, "event.nether_fishing_derby.bite_actionbar"));
                                }
                            }
                        }
                    }, (long) delaySec * 20L);
                    this.biteTasks.put(pid, biteTask);
                } else if (state != State.FAILED_ATTEMPT && state != State.IN_GROUND && state != State.CAUGHT_ENTITY && state != State.CAUGHT_FISH) {
                    if (state == State.REEL_IN) {
                        long now = System.currentTimeMillis();
                        long last = (Long) this.lastRewardAt.getOrDefault(pid, 0L);
                        if (now - last < this.cooldownMs) {
                            this.cancelTasks(pid);
                            this.castAt.remove(pid);
                            this.hookId.remove(pid);
                            this.biteReadyAt.remove(pid);
                        } else {
                            long started = (Long) this.castAt.getOrDefault(pid, 0L);
                            if (started > 0L && now - started >= (long) this.minLavaSeconds * 1000L) {
                                Material blockType = hook.getLocation().getBlock().getType();
                                if (blockType != Material.LAVA) {
                                    this.cancelTasks(pid);
                                    this.castAt.remove(pid);
                                    this.hookId.remove(pid);
                                    this.biteReadyAt.remove(pid);
                                } else if (event.getCaught() != null) {
                                    this.cancelTasks(pid);
                                    this.castAt.remove(pid);
                                    this.hookId.remove(pid);
                                    this.biteReadyAt.remove(pid);
                                } else {
                                    long biteReady = (Long) this.biteReadyAt.getOrDefault(pid, 0L);
                                    if (biteReady > 0L && now - biteReady <= this.biteWindowMs) {
                                        if (Math.random() > this.rewardChance) {
                                            this.actionbar(player, this.plugin.lang.tr(player, "event.nether_fishing_derby.no_loot_actionbar"));
                                            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.6F);
                                            this.cancelTasks(pid);
                                            this.castAt.remove(pid);
                                            this.hookId.remove(pid);
                                            this.biteReadyAt.remove(pid);
                                        } else {
                                            ItemStack reward = this.rollLavaLoot(ThreadLocalRandom.current());
                                            if (reward != null && reward.getType() != Material.AIR) {
                                                this.lastRewardAt.put(pid, now);
                                                this.cancelTasks(pid);
                                                this.castAt.remove(pid);
                                                this.hookId.remove(pid);
                                                this.biteReadyAt.remove(pid);
                                                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack[]{reward});
                                                leftovers.values().forEach((leftoverItem) -> player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem));
                                                player.getWorld().playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.7F, 1.2F);
                                                player.getWorld().spawnParticle(Particle.LAVA, player.getLocation().add((double) 0.0F, 1.2, (double) 0.0F), 12, 0.4, 0.3, 0.4, 0.01);
                                            } else {
                                                this.cancelTasks(pid);
                                                this.castAt.remove(pid);
                                                this.hookId.remove(pid);
                                                this.biteReadyAt.remove(pid);
                                            }
                                        }
                                    } else {
                                        this.actionbar(player, this.plugin.lang.tr(player, "event.nether_fishing_derby.no_bite_actionbar"));
                                        this.cancelTasks(pid);
                                        this.castAt.remove(pid);
                                        this.hookId.remove(pid);
                                        this.biteReadyAt.remove(pid);
                                    }
                                }
                            } else {
                                this.cancelTasks(pid);
                                this.castAt.remove(pid);
                                this.hookId.remove(pid);
                                this.biteReadyAt.remove(pid);
                            }
                        }
                    }
                } else {
                    this.cancelTasks(pid);
                    this.castAt.remove(pid);
                    this.hookId.remove(pid);
                    this.biteReadyAt.remove(pid);
                }
            }
        }
    }

    /**
     * 随机生成一份岩浆钓鱼战利品。
     */
    private ItemStack rollLavaLoot(ThreadLocalRandom rnd) {
        int roll = rnd.nextInt(1000);
        if (roll < 300) {
            return new ItemStack(Material.QUARTZ, 4 + rnd.nextInt(6));
        } else if (roll < 520) {
            return new ItemStack(Material.GOLD_NUGGET, 8 + rnd.nextInt(9));
        } else if (roll < 660) {
            return new ItemStack(Material.BLAZE_POWDER, 2 + rnd.nextInt(3));
        } else if (roll < 780) {
            return new ItemStack(Material.MAGMA_CREAM, 1 + rnd.nextInt(2));
        } else if (roll < 860) {
            return new ItemStack(Material.FIRE_CHARGE, 1);
        } else if (roll < 920) {
            return new ItemStack(Material.GHAST_TEAR, 1);
        } else if (roll < 930) {
            return new ItemStack(Material.OBSIDIAN, 1);
        } else if (roll < 970) {
            return new ItemStack(Material.IRON_INGOT, 2);
        } else if (roll < 985) {
            return this.fireProtectionBook(rnd);
        } else {
            return roll < 997 ? new ItemStack(Material.NETHERITE_SCRAP, 1) : new ItemStack(Material.WITHER_SKELETON_SKULL, 1);
        }
    }

    /**
     * 生成一本随机的火焰保护附魔书。
     */
    private ItemStack fireProtectionBook(ThreadLocalRandom rnd) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta != null) {
            int lvl = 2 + rnd.nextInt(3);
            meta.addStoredEnchant(Enchantment.FIRE_PROTECTION, lvl, true);
            book.setItemMeta(meta);
        }

        return book;
    }
}
