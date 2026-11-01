package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
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

public final class NetherFishingDerbyEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private int minLavaSeconds;
    private long cooldownMs;
    private double rewardChance;
    private int biteMinSeconds;
    private int biteMaxSeconds;
    private long biteWindowMs;
    private boolean keepHookOnSurface;
    private final Map<UUID, Long> castAt = new HashMap();
    private final Map<UUID, Long> lastRewardAt = new HashMap();
    private final Map<UUID, UUID> hookId = new HashMap();
    private final Map<UUID, Long> biteReadyAt = new HashMap();
    private final Map<UUID, BukkitTask> biteTasks = new HashMap();
    private final Map<UUID, BukkitTask> floatTasks = new HashMap();

    public NetherFishingDerbyEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.nether_fishing_derby.enabled", true);
        this.minDur = y.getInt("events.nether_fishing_derby.min_duration_days", 1);
        this.maxDur = y.getInt("events.nether_fishing_derby.max_duration_days", 1);
        this.baseChance = y.getDouble("events.nether_fishing_derby.base_chance_per_day", 0.08);
        this.minLavaSeconds = y.getInt("events.nether_fishing_derby.min_lava_seconds", 2);
        this.cooldownMs = y.getLong("events.nether_fishing_derby.cooldown_ms", 1200L);
        this.rewardChance = y.getDouble("events.nether_fishing_derby.reward_chance", 0.85);
        this.biteMinSeconds = y.getInt("events.nether_fishing_derby.bite_min_seconds", 2);
        this.biteMaxSeconds = y.getInt("events.nether_fishing_derby.bite_max_seconds", 6);
        this.biteWindowMs = y.getLong("events.nether_fishing_derby.bite_window_ms", 4500L);
        this.keepHookOnSurface = y.getBoolean("events.nether_fishing_derby.keep_hook_on_surface", true);
    }

    public String getId() {
        return "nether_fishing_derby";
    }

    public String getDisplayName() {
        return "Nether Fishing Derby";
    }

    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    public int getMinDurationDays() {
        return this.minDur;
    }

    public int getMaxDurationDays() {
        return this.maxDur;
    }

    public boolean canStartToday(CalendarState state, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else {
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((p) -> p.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState state, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendTitle(this.plugin.lang.tr(p, "event.nether_fishing_derby.title"), this.plugin.lang.tr(p, "event.nether_fishing_derby.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState state, EventContext ctx) {
        this.biteTasks.values().forEach(BukkitTask::cancel);
        this.floatTasks.values().forEach(BukkitTask::cancel);
        this.biteTasks.clear();
        this.floatTasks.clear();
        this.castAt.clear();
        this.lastRewardAt.clear();
        this.hookId.clear();
        this.biteReadyAt.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.nether_fishing_derby.end"));
            }
        }

    }

    public void onDayTick(CalendarState state, EventContext ctx) {
    }

    public void onTick(CalendarState state, EventContext ctx) {
    }

    private void actionbar(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
    }

    private void cancelTasks(UUID pid) {
        BukkitTask t1 = (BukkitTask)this.biteTasks.remove(pid);
        if (t1 != null) {
            t1.cancel();
        }

        BukkitTask t2 = (BukkitTask)this.floatTasks.remove(pid);
        if (t2 != null) {
            t2.cancel();
        }

    }

    private FishHook getHook(UUID hookUuid) {
        if (hookUuid == null) {
            return null;
        } else {
            Entity ent = Bukkit.getEntity(hookUuid);
            if (ent instanceof FishHook) {
                FishHook fh = (FishHook)ent;
                if (!fh.isDead()) {
                    return fh;
                }
            }

            return null;
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld().getEnvironment() == Environment.NETHER) {
            FishHook hook = e.getHook();
            if (hook != null) {
                UUID pid = p.getUniqueId();
                PlayerFishEvent.State st = e.getState();
                if (st == State.FISHING) {
                    this.castAt.put(pid, System.currentTimeMillis());
                    this.hookId.put(pid, hook.getUniqueId());
                    this.biteReadyAt.remove(pid);
                    this.cancelTasks(pid);
                    if (this.keepHookOnSurface) {
                        BukkitTask ft = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                            if (p.isOnline() && p.getWorld().getEnvironment() == Environment.NETHER) {
                                FishHook fh = this.getHook((UUID)this.hookId.get(pid));
                                if (fh != null) {
                                    if (fh.getLocation().getBlock().getType() == Material.LAVA) {
                                        Location loc = fh.getLocation();
                                        double surfaceY = (double)loc.getBlockY() + 0.12;
                                        if (loc.getY() < surfaceY) {
                                            Location n = loc.clone();
                                            n.setY(surfaceY);
                                            fh.teleport(n);
                                            fh.setVelocity(new Vector(0, 0, 0));
                                        }

                                    }
                                }
                            } else {
                                this.cancelTasks(pid);
                            }
                        }, 1L, 5L);
                        this.floatTasks.put(pid, ft);
                    }

                    int delaySec = ThreadLocalRandom.current().nextInt(Math.max(1, this.biteMinSeconds), Math.max(this.biteMinSeconds + 1, this.biteMaxSeconds + 1));
                    BukkitTask bt = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                        if (p.isOnline() && p.getWorld().getEnvironment() == Environment.NETHER) {
                            FishHook fh = this.getHook((UUID)this.hookId.get(pid));
                            if (fh != null) {
                                if (fh.getLocation().getBlock().getType() == Material.LAVA) {
                                    this.biteReadyAt.put(pid, System.currentTimeMillis());
                                    Location fx = fh.getLocation().clone().add((double)0.0F, (double)0.75F, (double)0.0F);
                                    World ww = fx.getWorld();
                                    ww.playSound(fx, Sound.ENTITY_BLAZE_SHOOT, 1.2F, 0.7F);
                                    ww.spawnParticle(Particle.LAVA, fx, 26, (double)0.25F, 0.08, (double)0.25F, 0.01);
                                    ww.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, fx, 10, 0.18, 0.05, 0.18, 0.01);
                                    ww.spawnParticle(Particle.ASH, fx, 14, (double)0.25F, 0.06, (double)0.25F, 0.01);
                                    ww.spawnParticle(Particle.FLAME, fx, 10, 0.2, 0.05, 0.2, 0.01);
                                    this.actionbar(p, this.plugin.lang.tr(p, "event.nether_fishing_derby.bite_actionbar"));
                                }
                            }
                        }
                    }, (long)delaySec * 20L);
                    this.biteTasks.put(pid, bt);
                } else if (st != State.FAILED_ATTEMPT && st != State.IN_GROUND && st != State.CAUGHT_ENTITY && st != State.CAUGHT_FISH) {
                    if (st == State.REEL_IN) {
                        long now = System.currentTimeMillis();
                        long last = (Long)this.lastRewardAt.getOrDefault(pid, 0L);
                        if (now - last < this.cooldownMs) {
                            this.cancelTasks(pid);
                            this.castAt.remove(pid);
                            this.hookId.remove(pid);
                            this.biteReadyAt.remove(pid);
                        } else {
                            long started = (Long)this.castAt.getOrDefault(pid, 0L);
                            if (started > 0L && now - started >= (long)this.minLavaSeconds * 1000L) {
                                Material in = hook.getLocation().getBlock().getType();
                                if (in != Material.LAVA) {
                                    this.cancelTasks(pid);
                                    this.castAt.remove(pid);
                                    this.hookId.remove(pid);
                                    this.biteReadyAt.remove(pid);
                                } else if (e.getCaught() != null) {
                                    this.cancelTasks(pid);
                                    this.castAt.remove(pid);
                                    this.hookId.remove(pid);
                                    this.biteReadyAt.remove(pid);
                                } else {
                                    long br = (Long)this.biteReadyAt.getOrDefault(pid, 0L);
                                    if (br > 0L && now - br <= this.biteWindowMs) {
                                        if (Math.random() > this.rewardChance) {
                                            this.actionbar(p, this.plugin.lang.tr(p, "event.nether_fishing_derby.no_loot_actionbar"));
                                            p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.6F);
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
                                                HashMap<Integer, ItemStack> leftovers = p.getInventory().addItem(new ItemStack[]{reward});
                                                leftovers.values().forEach((it) -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                                                p.getWorld().playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.7F, 1.2F);
                                                p.getWorld().spawnParticle(Particle.LAVA, p.getLocation().add((double)0.0F, 1.2, (double)0.0F), 12, 0.4, 0.3, 0.4, 0.01);
                                            } else {
                                                this.cancelTasks(pid);
                                                this.castAt.remove(pid);
                                                this.hookId.remove(pid);
                                                this.biteReadyAt.remove(pid);
                                            }
                                        }
                                    } else {
                                        this.actionbar(p, this.plugin.lang.tr(p, "event.nether_fishing_derby.no_bite_actionbar"));
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

    private ItemStack fireProtectionBook(ThreadLocalRandom rnd) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
        if (meta != null) {
            int lvl = 2 + rnd.nextInt(3);
            meta.addStoredEnchant(Enchantment.FIRE_PROTECTION, lvl, true);
            book.setItemMeta(meta);
        }

        return book;
    }
}
