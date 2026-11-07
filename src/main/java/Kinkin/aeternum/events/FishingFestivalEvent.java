package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerFishEvent.State;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 钓鱼节事件（Fishing Festival）。
 *
 * <p>每年最多触发若干次。事件期间，玩家主世界钓鱼成功时有几率获得：
 * <ul>
 *     <li>额外的“宝藏”掉落（附魔书、矿石、海产、附魔钓竿等）；</li>
 *     <li>双倍渔获。</li>
 * </ul>
 * 附魔“海之眷顾”与“诱饵”会按等级提升宝藏概率与双倍概率；触发时会有水花粒子与音效。</p>
 */
public final class FishingFestivalEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 是否启用全局粒子特效。
     */
    private boolean globalParticlesEnabled;
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
     * 每年最多触发次数。
     */
    private int maxPerYear;
    /**
     * 触发额外宝藏的概率。
     */
    private double treasureChance;
    /**
     * 触发双倍渔获的概率。
     */
    private double doubleCatchChance;
    /**
     * 记录每年已触发次数。
     */
    private final Map<Integer, Integer> usedPerYear = new HashMap();

    public FishingFestivalEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.fishing.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = config.getInt("events.fishing.min_duration_days", 1);
        this.maxDur = config.getInt("events.fishing.max_duration_days", 2);
        this.baseChance = config.getDouble("events.fishing.base_chance_per_day", 0.1);
        this.maxPerYear = config.getInt("events.fishing.max_per_year", 3);
        this.treasureChance = config.getDouble("events.fishing.treasure_chance", 0.4);
        this.doubleCatchChance = config.getDouble("events.fishing.double_catch_chance", 0.35);
    }

    @Override
    public String getId() {
        return "fishing_festival";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.fishing.display_name", "&bFishing Festival");
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
     * 判定是否可开始：已启用、本年次数未超上限且通过基础概率。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            int used = (Integer) this.usedPerYear.getOrDefault(state.year, 0);
            if (used >= this.maxPerYear) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始：累计本年触发次数，并向全服玩家发送标题。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.usedPerYear.put(state.year, (Integer) this.usedPerYear.getOrDefault(state.year, 0) + 1);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(player, "event.fishing.title");
            String sub = this.plugin.lang.tr(player, "event.fishing.subtitle");
            player.sendTitle(title, sub, 20, 80, 40);
        }

    }

    /**
     * 事件结束：向全服玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.fishing.end"));
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听钓鱼成功：按概率给予额外宝藏与双倍渔获，并播放特效。
     */
    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == State.CAUGHT_FISH) {
            Player player = event.getPlayer();
            if (player.getWorld().getEnvironment() == Environment.NORMAL) {
                if (this.globalParticlesEnabled) {
                    player.getWorld().spawnParticle(Particle.SPLASH, event.getHook().getLocation(), 16, 0.35, 0.12, 0.35, 0.03);
                }

                Entity caught = event.getCaught();
                if (caught instanceof Item) {
                    Item item = (Item) caught;
                    ItemStack original = item.getItemStack().clone();
                    ItemStack rod = this.getFishingRod(player);
                    int luck = 0;
                    int lure = 0;
                    if (rod != null) {
                        luck = rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA);
                        lure = rod.getEnchantmentLevel(Enchantment.LURE);
                    }

                    double rodFactor = (double) 1.0F + (double) luck * 0.35 + (double) lure * 0.2;
                    rodFactor = Math.min((double) 2.5F, rodFactor);
                    double treasureEffChance = Math.min(0.95, this.treasureChance * rodFactor);
                    double doubleEffChance = Math.min(0.9, this.doubleCatchChance * ((double) 0.5F + (double) 0.5F * rodFactor));
                    boolean didSomething = false;
                    boolean goodRod = luck + lure >= 2;
                    if (Math.random() < treasureEffChance) {
                        ItemStack treasure = this.randomTreasure(goodRod);
                        player.getInventory().addItem(new ItemStack[]{treasure});
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6F, 1.5F);
                        didSomething = true;
                    }

                    if (Math.random() < doubleEffChance) {
                        player.getInventory().addItem(new ItemStack[]{original});
                        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.7F, 1.2F);
                        didSomething = true;
                    }

                    if (didSomething) {
                        item.remove();
                    }

                }
            }
        }
    }

    /**
     * 获取玩家主手或副手中的钓鱼竿，没有则返回 null。
     */
    private ItemStack getFishingRod(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() == Material.FISHING_ROD) {
            return main;
        } else {
            ItemStack off = player.getInventory().getItemInOffHand();
            return off != null && off.getType() == Material.FISHING_ROD ? off : null;
        }
    }

    /**
     * 依据概率与钓竿品质随机生成一份“宝藏”物品。
     */
    private ItemStack randomTreasure(boolean goodRod) {
        double roll = Math.random();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (!goodRod) {
            if (roll < 0.4) {
                return new ItemStack(Material.EXPERIENCE_BOTTLE, rng.nextInt(3, 7));
            } else if (roll < (double) 0.75F) {
                return Math.random() < 0.6 ? new ItemStack(Material.NAUTILUS_SHELL, 1) : new ItemStack(Material.NAME_TAG, 1);
            } else if (roll < 0.9) {
                return Math.random() < 0.7 ? new ItemStack(Material.EMERALD, rng.nextInt(1, 4)) : new ItemStack(Material.DIAMOND, 1);
            } else {
                return Math.random() < 0.7 ? new ItemStack(Material.SADDLE, 1) : new ItemStack(Material.BONE_BLOCK, rng.nextInt(1, 3));
            }
        } else if (roll < 0.1) {
            return this.randomBlockOre(rng);
        } else if (roll < 0.3) {
            return this.randomValuableBook(rng);
        } else if (roll < (double) 0.5F) {
            return this.randomSeaStuff(rng);
        } else if (roll < 0.65) {
            return this.randomOreTreasure(rng);
        } else if (roll < 0.8) {
            return Math.random() < 0.8 ? this.randomEnchantedRod(rng) : new ItemStack(Material.HEART_OF_THE_SEA, 1);
        } else {
            return Math.random() < 0.6 ? new ItemStack(Material.EXPERIENCE_BOTTLE, rng.nextInt(6, 12)) : new ItemStack(Material.NAUTILUS_SHELL, rng.nextInt(1, 3));
        }
    }

    /**
     * 随机生成矿石类宝藏。
     */
    private ItemStack randomOreTreasure(ThreadLocalRandom rng) {
        double roll = Math.random();
        if (roll < 0.45) {
            return new ItemStack(Material.DIAMOND, rng.nextInt(1, 3));
        } else if (roll < (double) 0.75F) {
            Material metal = rng.nextBoolean() ? Material.IRON_INGOT : Material.GOLD_INGOT;
            return new ItemStack(metal, rng.nextInt(5, 13));
        } else if (roll < 0.9) {
            return new ItemStack(Material.NETHERITE_SCRAP, 1);
        } else {
            Material block = rng.nextBoolean() ? Material.REDSTONE_BLOCK : Material.LAPIS_BLOCK;
            return new ItemStack(block, rng.nextInt(1, 3));
        }
    }

    /**
     * 随机生成矿石方块类宝藏。
     */
    private ItemStack randomBlockOre(ThreadLocalRandom rng) {
        if (Math.random() < 0.15) {
            return new ItemStack(Material.ANCIENT_DEBRIS, 1);
        } else {
            Material[] ores = new Material[]{Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK};
            Material block = ores[rng.nextInt(ores.length)];
            return new ItemStack(block, 1);
        }
    }

    /**
     * 随机生成海洋类宝藏（海绵、海晶石、海晶灯、珊瑚块）。
     */
    private ItemStack randomSeaStuff(ThreadLocalRandom rng) {
        double roll = Math.random();
        if (roll < 0.35) {
            Material sponge = Math.random() < 0.7 ? Material.SPONGE : Material.WET_SPONGE;
            return new ItemStack(sponge, 1);
        } else if (roll < 0.65) {
            Material prismarine = Math.random() < (double) 0.5F ? Material.PRISMARINE : Material.DARK_PRISMARINE;
            return new ItemStack(prismarine, rng.nextInt(5, 11));
        } else if (roll < 0.85) {
            return new ItemStack(Material.SEA_LANTERN, rng.nextInt(1, 4));
        } else {
            Material[] corals = new Material[]{Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK, Material.FIRE_CORAL_BLOCK};
            Material coral = corals[rng.nextInt(corals.length)];
            return new ItemStack(coral, rng.nextInt(3, 9));
        }
    }

    /**
     * 随机生成一本附魔书（可能包含多个附魔）。
     */
    private ItemStack randomValuableBook(ThreadLocalRandom rng) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta == null) {
            return new ItemStack(Material.EXPERIENCE_BOTTLE, rng.nextInt(6, 12));
        } else {
            Map<Enchantment, int[]> enchantmentLevels = new HashMap();
            enchantmentLevels.put(Enchantment.MENDING, new int[]{1, 1});
            enchantmentLevels.put(Enchantment.LUCK_OF_THE_SEA, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.LURE, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.UNBREAKING, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.AQUA_AFFINITY, new int[]{1, 1});
            enchantmentLevels.put(Enchantment.RESPIRATION, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.DEPTH_STRIDER, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.RIPTIDE, new int[]{1, 3});
            enchantmentLevels.put(Enchantment.PROTECTION, new int[]{1, 4});
            enchantmentLevels.put(Enchantment.PROJECTILE_PROTECTION, new int[]{1, 4});
            enchantmentLevels.put(Enchantment.VANISHING_CURSE, new int[]{1, 1});
            List<Enchantment> availableEnchants = (List) enchantmentLevels.keySet().stream().filter((enchantment) -> !enchantment.equals(Enchantment.MENDING)).collect(Collectors.toList());
            if (Math.random() < 0.15) {
                meta.addStoredEnchant(Enchantment.MENDING, 1, true);
            }

            if (meta.getStoredEnchants().isEmpty() || Math.random() < 0.7) {
                Enchantment ench = (Enchantment) availableEnchants.get(rng.nextInt(availableEnchants.size()));
                int[] levels = (int[]) enchantmentLevels.get(ench);
                int level = rng.nextInt(levels[0], levels[1] + 1);
                if (!meta.hasStoredEnchant(ench)) {
                    meta.addStoredEnchant(ench, level, true);
                }
            }

            if (Math.random() < 0.15) {
                Enchantment ench = (Enchantment) availableEnchants.get(rng.nextInt(availableEnchants.size()));
                int[] levels = (int[]) enchantmentLevels.get(ench);
                int level = rng.nextInt(levels[0], levels[1] + 1);
                if (!meta.hasStoredEnchant(ench)) {
                    meta.addStoredEnchant(ench, level, true);
                }
            }

            if (meta.getStoredEnchants().isEmpty()) {
                Enchantment ench = rng.nextBoolean() ? Enchantment.LUCK_OF_THE_SEA : Enchantment.LURE;
                meta.addStoredEnchant(ench, rng.nextInt(1, 3), true);
            }

            book.setItemMeta(meta);
            return book;
        }
    }

    /**
     * 随机生成一把附魔钓鱼竿。
     */
    private ItemStack randomEnchantedRod(ThreadLocalRandom rng) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        int luckLvl = rng.nextInt(1, 3);
        int unbLvl = rng.nextInt(1, 3);
        if (Math.random() < 0.3) {
            int lureLvl = rng.nextInt(1, 3);
            rod.addUnsafeEnchantment(Enchantment.LURE, lureLvl);
        }

        rod.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, luckLvl);
        rod.addUnsafeEnchantment(Enchantment.UNBREAKING, unbLvl);
        if (Math.random() < 0.1) {
            rod.addUnsafeEnchantment(Enchantment.MENDING, 1);
        }

        return rod;
    }
}
