package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 新年事件（New Year）。
 *
 * <p>在一年第一天（或配置的首个月份第一天）触发。事件开始时向玩家发送标题并
 * 发放火箭与火药礼包；随后在约 60 秒内持续在玩家周围随机生成烟花粒子与音效。</p>
 */
public final class NewYearEvent implements SeasonalEvent {
    private static final String OVERWORLD_CHANNEL_PATH = "calendar.channels.OVERWORLD.months";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 是否启用全局粒子特效。
     */
    private boolean globalParticlesEnabled;
    /**
     * 每位玩家每波触发烟花的概率。
     */
    private double fireworksChancePerPlayer;
    /**
     * 每波烟花的爆点数量。
     */
    private int fireworksBurstsPerWave;
    /**
     * 礼包中火箭的最小数量。
     */
    private int giftRocketsMin;
    /**
     * 礼包中火箭的最大数量。
     */
    private int giftRocketsMax;
    /**
     * 礼包中火药的最小数量。
     */
    private int giftGunpowderMin;
    /**
     * 礼包中火药的最大数量。
     */
    private int giftGunpowderMax;
    /**
     * 剩余烟花持续秒数。
     */
    private int fireworksSecondsLeft = 0;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();

    public NewYearEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.new_year.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.fireworksChancePerPlayer = config.getDouble("events.new_year.fireworks_chance", 0.6);
        this.fireworksBurstsPerWave = config.getInt("events.new_year.fireworks_bursts", 3);
        this.giftRocketsMin = config.getInt("events.new_year.gift_rockets_min", 12);
        this.giftRocketsMax = config.getInt("events.new_year.gift_rockets_max", 24);
        this.giftGunpowderMin = config.getInt("events.new_year.gift_gunpowder_min", 6);
        this.giftGunpowderMax = config.getInt("events.new_year.gift_gunpowder_max", 12);
    }

    @Override
    public String getId() {
        return "new_year";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.new_year.display_name", "&eNew Year");
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
     * 判断是否为新年第 1 天：未启用月份时为春季第 1 天，否则为配置首月第 1 天。
     */
    private boolean isNewYear(CalendarState state) {
        if (state == null) {
            return false;
        } else if (!state.monthsEnabled) {
            return state.season == Season.SPRING && state.day == 1;
        } else {
            String firstMonthId = this.getFirstConfiguredMonthId();
            if (firstMonthId != null && state.monthId != null && !state.monthId.isBlank()) {
                return state.day == 1 && firstMonthId.equalsIgnoreCase(state.monthId);
            } else {
                return false;
            }
        }
    }

    /**
     * 返回配置顺序中的第一个月份 id（无则 null）。
     */
    private String getFirstConfiguredMonthId() {
        ConfigurationSection monthsSec = this.plugin.cfg.calendar.getConfigurationSection("calendar.channels.OVERWORLD.months");
        if (monthsSec != null && monthsSec.getBoolean("enabled", false)) {
            List<String> order = monthsSec.getStringList("order");
            if (order != null && !order.isEmpty()) {
                for (String monthId : order) {
                    if (monthId != null && !monthId.isBlank()) {
                        return monthId.trim();
                    }
                }

                return null;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * 判定今天是否为新年。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        return this.enabled && this.isNewYear(state);
    }

    /**
     * 事件开始：向非旁观玩家发送标题、发放礼包并启动烟花计时。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        for (World world : context.overworlds()) {
            for (Player player : world.getPlayers()) {
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    String title = this.plugin.lang.tr(player, "event.new_year.title");
                    String sub = this.plugin.lang.tr(player, "event.new_year.subtitle");
                    player.sendTitle(title, sub, 20, 80, 40);
                    this.giveGift(player);
                }
            }
        }

        this.fireworksSecondsLeft = 60;
    }

    /**
     * 事件结束：向全服玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.new_year.end"));
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    /**
     * 每个 tick 递减烟花计时，并按概率为各世界玩家生成烟花。
     */
    @Override
    public void onTick(CalendarState state, EventContext context) {
        if (this.fireworksSecondsLeft > 0) {
            --this.fireworksSecondsLeft;

            for (World world : context.overworlds()) {
                for (Player player : world.getPlayers()) {
                    if (player.getGameMode() != GameMode.SPECTATOR && !(this.rnd.nextDouble() > this.fireworksChancePerPlayer)) {
                        this.spawnFireworksAroundPlayer(player, world);
                    }
                }
            }

        }
    }

    /**
     * 向玩家发放火箭与火药并播放提示音效。
     */
    private void giveGift(Player player) {
        int rockets = this.randomBetween(this.giftRocketsMin, this.giftRocketsMax);
        int powder = this.randomBetween(this.giftGunpowderMin, this.giftGunpowderMax);
        if (rockets > 0) {
            player.getInventory().addItem(new ItemStack[]{new ItemStack(Material.FIREWORK_ROCKET, rockets)});
        }

        if (powder > 0) {
            player.getInventory().addItem(new ItemStack[]{new ItemStack(Material.GUNPOWDER, powder)});
        }

        player.sendMessage(this.plugin.lang.tr(player, "event.new_year.gift_found"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
    }

    /**
     * 在 [min,max] 区间内随机取值（max 小于等于 min 时返回 min）。
     */
    private int randomBetween(int min, int max) {
        return max <= min ? min : this.rnd.nextInt(min, max + 1);
    }

    /**
     * 在玩家周围天空中随机生成数波烟花粒子与爆炸音效。
     */
    private void spawnFireworksAroundPlayer(Player player, World world) {
        Location base = player.getLocation();

        for (int i = 0; i < this.fireworksBurstsPerWave; ++i) {
            int dx = this.rnd.nextInt(-12, 13);
            int dz = this.rnd.nextInt(-12, 13);
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            Block top = world.getHighestBlockAt(x, z);
            Location loc = top.getLocation().add((double) 0.5F, (double) 6.0F + (double) this.rnd.nextInt(4), (double) 0.5F);
            if (this.globalParticlesEnabled) {
                world.spawnParticle(Particle.FIREWORK, loc, 40, (double) 0.5F, (double) 1.0F, (double) 0.5F, 0.01);
                world.spawnParticle(Particle.EXPLOSION, loc, 1, (double) 0.0F, (double) 0.0F, (double) 0.0F, (double) 0.0F);
                world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.6F, 1.2F);
                world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9F, 1.0F);
            }
        }

    }
}
