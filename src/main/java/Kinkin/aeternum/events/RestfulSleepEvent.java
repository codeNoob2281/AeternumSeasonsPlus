package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 安眠事件（Restful Sleep）。
 *
 * <p>事件激活期间，玩家在主世界进入睡眠即可获得“生命提升”buff（持续一定秒数）。
 * 若玩家在受 buff 影响期间受到伤害后血量降到阈值以下，则立即移除该 buff 并提示。
 * 配置来自 {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class RestfulSleepEvent implements SeasonalEvent {
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
     * 生命提升 buff 的持续秒数。
     */
    private int healthBoostSeconds;

    public RestfulSleepEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.restful_sleep.enabled", true);
        this.minDur = config.getInt("events.restful_sleep.min_duration_days", 2);
        this.maxDur = config.getInt("events.restful_sleep.max_duration_days", 4);
        this.baseChance = config.getDouble("events.restful_sleep.base_chance_per_day", 0.1);
        this.healthBoostSeconds = config.getInt("events.restful_sleep.health_boost_seconds", 600);
    }

    @Override
    public String getId() {
        return "restful_sleep";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.restful_sleep.display_name", "&dRestful Sleep");
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
     * 仅当通过基础概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            return Math.random() < this.baseChance;
        }
    }

    /**
     * 事件开始时向所有在线玩家发送标题提示。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(player, "event.restful_sleep.title");
            String subtitle = this.plugin.lang.tr(player, "event.restful_sleep.subtitle");
            player.sendTitle(title, subtitle, 20, 80, 40);
        }

    }

    /**
     * 事件结束时通知所有在线玩家，并移除其生命提升 buff。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.restful_sleep.end"));
            player.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听上床事件：玩家在主世界入睡时发送安眠提示。
     */
    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!event.isCancelled()) {
            Player player = event.getPlayer();
            if (player.getWorld().getEnvironment() == Environment.NORMAL) {
                player.sendMessage(this.plugin.lang.tr(player, "event.restful_sleep.sleeping"));
            }
        }
    }

    /**
     * 监听起床事件：非旁观者起床后获得生命提升 buff。
     */
    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, this.healthBoostSeconds * 20, 0, true, false, true));
            player.sendMessage(this.plugin.lang.tr(player, "event.restful_sleep.buffed"));
        }
    }

    /**
     * 监听受伤事件：携带生命提升 buff 的玩家血量过低时移除 buff 并提示。
     */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            if (player.hasPotionEffect(PotionEffectType.HEALTH_BOOST)) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    if (player.getHealth() <= (double) 10.0F) {
                        player.removePotionEffect(PotionEffectType.HEALTH_BOOST);
                        player.sendMessage(this.plugin.lang.tr(player, "event.restful_sleep.lost"));
                    }

                }, 1L);
            }
        }
    }
}
