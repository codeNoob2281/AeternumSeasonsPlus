package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 节日事件（Holiday）。
 *
 * <p>在特定日历日触发，按日期分为两种模式：
 * <ul>
 *     <li>万圣节（Halloween）：秋季最后一个月的前两天，夜晚会给附近敌对生物施加力量/速度，
 *         并让它们戴上雕刻南瓜，同时播放灵魂火焰与熔岩粒子；</li>
 *     <li>圣诞节（Christmas）：冬季末月接近月末的那一天，向玩家发放节日礼包，
 *         并在持续期间为周围玩家缓慢回血、播放雪与村民粒子。</li>
 * </ul>
 * 模式按世界记录于 {@link #activeModes}，在 {@code onTick} 中分别驱动。</p>
 */
public final class HolidayEvent implements SeasonalEvent {
    private static final String OVERWORLD_CHANNEL_PATH = "calendar.channels.OVERWORLD.months";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 是否启用全局粒子特效。
     */
    private boolean globalParticlesEnabled;
    /**
     * 万圣节给附近敌对生物施加增益的半径。
     */
    private double halloweenMobBuffRadius;
    /**
     * 万圣节给敌对生物戴南瓜的概率。
     */
    private double halloweenPumpkinChance;
    /**
     * 万圣节给敌对生物施加增益的概率。
     */
    private double halloweenBuffChance;
    /**
     * 圣诞节回血的半径。
     */
    private double christmasHealRadius;
    /**
     * 圣诞节每次回血的量。
     */
    private double christmasHealAmount;
    /**
     * 圣诞节在季节中的哪一天（由每季天数推算）。
     */
    private int christmasDay;
    /**
     * 记录每个世界当前所处的节日模式。
     */
    private final Map<World, Mode> activeModes = new HashMap();

    public HolidayEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项，并据此推算圣诞节日期。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.holiday.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.halloweenMobBuffRadius = config.getDouble("events.holiday.halloween.mob_radius", (double) 32.0F);
        this.halloweenPumpkinChance = config.getDouble("events.holiday.halloween.pumpkin_chance", 0.9);
        this.halloweenBuffChance = config.getDouble("events.holiday.halloween.buff_chance", (double) 1.0F);
        this.christmasHealRadius = config.getDouble("events.holiday.christmas.heal_radius", (double) 12.0F);
        this.christmasHealAmount = config.getDouble("events.holiday.christmas.heal_amount", (double) 0.5F);
        int daysPerSeason = this.resolveDaysPerSeason();
        this.christmasDay = Math.max(1, daysPerSeason - 3);
    }

    /**
     * 解析每季天数配置（兼容两种键名，缺省 28，且至少为 4）。
     */
    private int resolveDaysPerSeason() {
        int days = this.plugin.cfg.calendar.getInt("days_per_season", Integer.MIN_VALUE);
        if (days == Integer.MIN_VALUE) {
            days = this.plugin.cfg.calendar.getInt("calendar.days_per_season", 28);
        }

        if (days < 4) {
            days = 4;
        }

        return days;
    }

    @Override
    public String getId() {
        return "holiday";
    }

    @Override
    public String getDisplayName() {
        return "Festividades";
    }

    @Override
    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    @Override
    public int getMinDurationDays() {
        return 1;
    }

    @Override
    public int getMaxDurationDays() {
        return 1;
    }

    /**
     * 判断是否为万圣节：未启用月份时为秋季第 1~2 天，否则为秋季最后配置月的前两天。
     */
    private boolean isHalloweenDay(CalendarState state) {
        if (state == null) {
            return false;
        } else if (!state.monthsEnabled) {
            return state.season == Season.AUTUMN && (state.day == 1 || state.day == 2);
        } else {
            String targetMonth = this.getLastConfiguredMonthForSeason(Season.AUTUMN);
            if (targetMonth != null && state.monthId != null && !state.monthId.isBlank()) {
                return targetMonth.equalsIgnoreCase(state.monthId) && (state.day == 1 || state.day == 2);
            } else {
                return false;
            }
        }
    }

    /**
     * 判断是否为圣诞节：未启用月份时为冬季第 christmasDay 天，否则为冬季最后配置月接近月末的一天。
     */
    private boolean isChristmasDay(CalendarState state) {
        if (state == null) {
            return false;
        } else if (!state.monthsEnabled) {
            return state.season == Season.WINTER && state.day == this.christmasDay;
        } else {
            String targetMonth = this.getLastConfiguredMonthForSeason(Season.WINTER);
            if (targetMonth != null && state.monthId != null && !state.monthId.isBlank()) {
                if (!targetMonth.equalsIgnoreCase(state.monthId)) {
                    return false;
                } else {
                    int maxDay = state.daysInMonth > 0 ? state.daysInMonth : 25;
                    int effectiveChristmasDay = Math.max(1, Math.min(25, maxDay));
                    return state.season == Season.WINTER && state.day == effectiveChristmasDay;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * 返回指定季节在配置中出现顺序里的最后一个月 id（无则 null）。
     */
    private String getLastConfiguredMonthForSeason(Season season) {
        ConfigurationSection monthsSec = this.plugin.cfg.calendar.getConfigurationSection("calendar.channels.OVERWORLD.months");
        if (monthsSec != null && monthsSec.getBoolean("enabled", false)) {
            List<String> order = monthsSec.getStringList("order");
            ConfigurationSection defs = monthsSec.getConfigurationSection("definitions");
            if (order != null && !order.isEmpty() && defs != null) {
                String found = null;

                for (String monthId : order) {
                    if (monthId != null && !monthId.isBlank()) {
                        ConfigurationSection def = defs.getConfigurationSection(monthId);
                        if (def != null) {
                            String rawSeason = def.getString("season", "");

                            try {
                                Season monthSeason = Season.valueOf(rawSeason.trim().toUpperCase(Locale.ROOT));
                                if (monthSeason == season) {
                                    found = monthId.trim();
                                }
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }

                return found;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * 判定今天是否为万圣节或圣诞节。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            return this.isHalloweenDay(state) || this.isChristmasDay(state);
        }
    }

    /**
     * 事件开始：确定节日模式并按世界记录，随后发放礼包/发送标题。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.activeModes.clear();
        Mode mode;
        if (this.isHalloweenDay(state)) {
            mode = HolidayEvent.Mode.HALLOWEEN;
        } else {
            if (!this.isChristmasDay(state)) {
                return;
            }

            mode = HolidayEvent.Mode.CHRISTMAS;
        }

        for (World world : context.overworlds()) {
            this.activeModes.put(world, mode);
        }

        if (mode == HolidayEvent.Mode.HALLOWEEN) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                String title = this.plugin.lang.tr(player, "event.halloween.title");
                String sub = this.plugin.lang.tr(player, "event.halloween.subtitle");
                player.sendTitle(title, sub, 20, 80, 40);
            }
        } else {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            for (World world : context.overworlds()) {
                for (Player player : world.getPlayers()) {
                    this.giveChristmasGiftToPlayer(player, rnd);
                }
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                String title = this.plugin.lang.tr(player, "event.christmas.title");
                String sub = this.plugin.lang.tr(player, "event.christmas.subtitle");
                player.sendTitle(title, sub, 20, 80, 40);
            }
        }

    }

    /**
     * 事件结束：清空模式记录并向全服玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.activeModes.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.holiday.end"));
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    /**
     * 每个 tick 按各世界所处模式执行万圣节或圣诞节效果。
     */
    @Override
    public void onTick(CalendarState state, EventContext context) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (World world : context.overworlds()) {
            Mode mode = (Mode) this.activeModes.get(world);
            if (mode != null) {
                if (mode == HolidayEvent.Mode.HALLOWEEN) {
                    this.tickHalloween(world, random);
                } else {
                    this.tickChristmas(world, random);
                }
            }
        }

    }

    /**
     * 万圣节每 tick 效果：对玩家附近敌对生物施加增益并戴南瓜，附粒子。
     */
    private void tickHalloween(World world, ThreadLocalRandom random) {
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                Location loc = player.getLocation().clone().add((double) 0.0F, (double) 1.0F, (double) 0.0F);
                if (this.globalParticlesEnabled) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.getX(), loc.getY(), loc.getZ(), 10, 0.7, 0.7, 0.7, 0.01);
                    world.spawnParticle(Particle.DRIPPING_LAVA, loc.getX(), loc.getY() + 0.3, loc.getZ(), 6, (double) 0.5F, (double) 0.5F, (double) 0.5F, 0.01);
                }

                for (Entity entity : world.getNearbyEntities(loc, this.halloweenMobBuffRadius, this.halloweenMobBuffRadius, this.halloweenMobBuffRadius)) {
                    if (entity instanceof Monster) {
                        Monster mob = (Monster) entity;
                        if (!mob.isDead()) {
                            if (random.nextDouble() <= this.halloweenBuffChance) {
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 0, true, false, false));
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0, true, false, false));
                            }

                            if (random.nextDouble() <= this.halloweenPumpkinChance && mob.getEquipment() != null) {
                                ItemStack helmet = mob.getEquipment().getHelmet();
                                if (helmet == null || helmet.getType() == Material.AIR) {
                                    mob.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
                                }
                            }

                            if (this.globalParticlesEnabled) {
                                Location ml = mob.getLocation();
                                world.spawnParticle(Particle.SOUL, ml.getX(), ml.getY() + mob.getHeight() * (double) 0.5F, ml.getZ(), 8, (double) 0.5F, (double) 0.5F, (double) 0.5F, 0.02);
                            }
                        }
                    }
                }
            }
        }

    }

    /**
     * 圣诞节每 tick 效果：为玩家附近玩家缓慢回血，附雪与村民粒子。
     */
    private void tickChristmas(World world, ThreadLocalRandom random) {
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                Location loc = player.getLocation().clone().add((double) 0.0F, 1.2, (double) 0.0F);
                if (this.globalParticlesEnabled) {
                    world.spawnParticle(Particle.SNOWFLAKE, loc.getX(), loc.getY(), loc.getZ(), 10, 0.8, 0.8, 0.8, 0.01);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, loc.getX(), loc.getY(), loc.getZ(), 6, 0.6, 0.6, 0.6, 0.01);
                }

                for (Entity entity : world.getNearbyEntities(loc, this.christmasHealRadius, this.christmasHealRadius, this.christmasHealRadius)) {
                    if (entity instanceof Player) {
                        Player other = (Player) entity;
                        if (!(other.getHealth() <= (double) 0.0F)) {
                            double max = ((AttributeInstance) Objects.requireNonNull(other.getAttribute(Attribute.MAX_HEALTH))).getValue();
                            double newHealth = Math.min(max, other.getHealth() + this.christmasHealAmount);
                            other.setHealth(newHealth);
                        }
                    }
                }
            }
        }

    }

    /**
     * 向玩家发放一个随机的圣诞节礼包（背包满则掉落在地上）。
     */
    private void giveChristmasGiftToPlayer(Player player, ThreadLocalRandom random) {
        ItemStack gift = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) gift.getItemMeta();
        String displayName = this.plugin.lang.tr(player, "event.christmas.gift_bundle_name");
        List<String> lore = Arrays.asList(this.plugin.lang.tr(player, "event.christmas.gift_bundle_lore_1"), this.plugin.lang.tr(player, "event.christmas.gift_bundle_lore_2"));
        meta.setDisplayName(displayName);
        meta.setLore(lore);
        meta.addItem(new ItemStack(Material.COOKIE, 8 + random.nextInt(9)));
        meta.addItem(new ItemStack(Material.CAKE, 1));
        if (random.nextDouble() < 0.6) {
            meta.addItem(new ItemStack(Material.GOLDEN_APPLE, 1 + random.nextInt(2)));
        }

        meta.addItem(new ItemStack(Material.EMERALD, 3 + random.nextInt(6)));
        meta.addItem(new ItemStack(Material.FIREWORK_ROCKET, 4 + random.nextInt(6)));
        meta.addItem(new ItemStack(Material.SNOWBALL, 16));
        gift.setItemMeta(meta);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{gift});
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), gift);
        }

        player.sendMessage(this.plugin.lang.tr(player, "event.christmas.gift_found"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
    }

    /**
     * 节日模式：万圣节或圣诞节。
     */
    private enum Mode {
        HALLOWEEN,
        CHRISTMAS;

        Mode() {
        }

        // $FF: synthetic method
        private static Mode[] $values() {
            return new Mode[]{HALLOWEEN, CHRISTMAS};
        }
    }
}
