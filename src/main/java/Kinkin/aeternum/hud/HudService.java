package kinkin.aeternum.hud;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarChannel;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.util.PlayerArrivalSync;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * HUD 显示服务。
 *
 * <p>通过 BossBar 与 ActionBar 向玩家展示当前季节/年份/天数（以及月份）信息。
 * 支持三种显示模式：{@link HudMode#FIXED} 常驻显示、{@link HudMode#VARIABLE} 仅在特定时段显示、
 * {@link HudMode#OFF} 关闭。BossBar 颜色可随季节变化，文本按玩家语言本地化。</p>
 *
 * <p>依赖：{@link SeasonService} 提供日历状态；{@link PlayerArrivalSync} 在传送/换世界/重生后
 * 触发重新绑定，避免客户端界面丢失。服务以固定周期任务刷新所有在线玩家。</p>
 */
public final class HudService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    /**
     * 每个玩家对应的 BossBar 实例。
     */
    private final Map<UUID, BossBar> bars = new HashMap();
    /**
     * 玩家显式设置的显示模式。
     */
    private final Map<UUID, HudMode> modes = new HashMap();
    /**
     * 使用 VARIABLE 模式的玩家。
     */
    private final Set<UUID> variablePlayers = new HashSet();
    /**
     * 使用 OFF 模式的玩家。
     */
    private final Set<UUID> offPlayers = new HashSet();
    private BukkitTask task;
    private PlayerArrivalSync arrivalSync;
    private final boolean bossbarEnabled;
    private final boolean actionbarEnabled;
    private final boolean actionbarClearOnHide;
    private final boolean colorBySeason;
    private final long updateTicks;
    private final HudMode defaultMode;
    /**
     * 每个玩家上次 ActionBar 文本，用于避免重复刷新。
     */
    private final Map<UUID, String> lastActionbarText = new HashMap();
    /**
     * 每个玩家当前 ActionBar 是否处于显示状态。
     */
    private final Set<UUID> actionbarShown = new HashSet();

    public HudService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.bossbarEnabled = plugin.cfg.hud.getBoolean("bossbar.enabled", true);
        this.actionbarEnabled = plugin.cfg.hud.getBoolean("actionbar.enabled", false);
        this.actionbarClearOnHide = plugin.cfg.hud.getBoolean("actionbar.clear_on_hide", false);
        this.colorBySeason = plugin.cfg.hud.getBoolean("bossbar.color_by_season", true);
        this.updateTicks = plugin.cfg.hud.getLong("bossbar.update_ticks", 40L);
        String rawDefault = plugin.cfg.hud.getString("bossbar.default_mode", "FIXED");

        HudMode defaultMode;
        try {
            defaultMode = HudService.HudMode.valueOf(rawDefault.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidDefault) {
            defaultMode = HudService.HudMode.FIXED;
        }

        this.defaultMode = defaultMode;

        for (String rawId : plugin.cfg.hud.getStringList("bossbar.variable_players")) {
            try {
                this.variablePlayers.add(UUID.fromString(rawId));
            } catch (IllegalArgumentException invalidVariableId) {
            }
        }

        for (String rawId : plugin.cfg.hud.getStringList("bossbar.off_players")) {
            try {
                this.offPlayers.add(UUID.fromString(rawId));
            } catch (IllegalArgumentException invalidOffId) {
            }
        }

    }

    /**
     * 根据玩家 UUID 生成稳定的 BossBar 命名空间键（避免同名冲突）。
     */
    private NamespacedKey barKey(UUID id) {
        String compactId = id.toString().replace("-", "");
        String key = "hud_" + compactId.toLowerCase(Locale.ROOT);
        return new NamespacedKey(this.plugin, key);
    }

    /**
     * 清理并移除某玩家的 BossBar（从所有接收者移除并注销）。
     */
    private void cleanupBossBar(UUID id) {
        NamespacedKey key = this.barKey(id);
        BossBar old = Bukkit.getBossBar(key);
        if (old != null) {
            old.removeAll();
            old.setVisible(false);
            Bukkit.removeBossBar(key);
        }

        this.bars.remove(id);
    }

    /**
     * 清理所有在线玩家的 BossBar（用于重载/卸载前重置）。
     */
    private void cleanupBossBarsForOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.cleanupBossBar(player.getUniqueId());
        }

    }

    /**
     * 注册监听器与刷新任务，并重建在线玩家的 BossBar。
     */
    public void register() {
        if (this.bossbarEnabled || this.actionbarEnabled) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            if (this.arrivalSync != null) {
                this.arrivalSync.close();
            }

            this.arrivalSync = new PlayerArrivalSync(this.plugin, this::resyncPlayer);
            this.cleanupBossBarsForOnlinePlayers();
            if (this.bossbarEnabled) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    this.ensureBar(player);
                }
            }

            if (this.task != null) {
                this.task.cancel();
            }

            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 1L, this.updateTicks);
        }
    }

    /**
     * 注销监听器、停止刷新任务并清理全部 BossBar 与缓存。
     */
    public void unregister() {
        if (this.arrivalSync != null) {
            this.arrivalSync.close();
        }

        this.arrivalSync = null;
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.cleanupBossBarsForOnlinePlayers();
        this.bars.clear();
        this.modes.clear();
        this.variablePlayers.clear();
        this.offPlayers.clear();
        this.lastActionbarText.clear();
        this.actionbarShown.clear();
    }

    /**
     * 玩家加入时为其准备 BossBar（先清理可能残留的旧实例）。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.bossbarEnabled) {
            this.cleanupBossBar(event.getPlayer().getUniqueId());
            this.ensureBar(event.getPlayer());
        }

    }

    /**
     * 玩家重生事件（BossBar 由到达同步器统一重新绑定，这里无需处理）。
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
    }

    /**
     * 玩家切换世界后重新同步 HUD。
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        this.resyncPlayer(event.getPlayer());
    }

    /**
     * 玩家到达新位置后重置其 ActionBar 缓存并刷新 HUD。
     */
    private void resyncPlayer(Player player) {
        if (this.bossbarEnabled) {
            this.cleanupBossBar(player.getUniqueId());
        }

        this.lastActionbarText.remove(player.getUniqueId());
        this.actionbarShown.remove(player.getUniqueId());
        this.refreshPlayer(player);
    }

    /**
     * 玩家下线时清理 BossBar、ActionBar 状态与模式缓存。
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.cleanupBossBar(id);
        if (this.actionbarEnabled && this.actionbarClearOnHide) {
            event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }

        this.lastActionbarText.remove(id);
        this.actionbarShown.remove(id);
        this.modes.remove(id);
    }

    /**
     * 确保玩家拥有可用的 BossBar（复用同名已存在的实例，否则新建并添加玩家）。
     */
    private void ensureBar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = (BossBar) this.bars.get(id);
        if (bar == null) {
            NamespacedKey key = this.barKey(id);
            BossBar existing = Bukkit.getBossBar(key);
            if (existing != null) {
                existing.removeAll();
                existing.setVisible(false);
                bar = existing;
            } else {
                bar = Bukkit.createBossBar(key, "", BarColor.WHITE, BarStyle.SEGMENTED_10, new BarFlag[0]);
            }

            this.bars.put(id, bar);
        }

        if (!bar.getPlayers().contains(p)) {
            bar.addPlayer(p);
        }

    }

    /**
     * 设置玩家的 HUD 显示模式并持久化到 hud.yml。
     */
    public void setPlayerMode(Player p, HudMode mode) {
        UUID id = p.getUniqueId();
        this.modes.put(id, mode);
        if (mode == HudService.HudMode.OFF) {
            this.offPlayers.add(id);
            this.variablePlayers.remove(id);
        } else if (mode == HudService.HudMode.VARIABLE) {
            this.variablePlayers.add(id);
            this.offPlayers.remove(id);
        } else {
            this.variablePlayers.remove(id);
            this.offPlayers.remove(id);
        }

        if (this.bossbarEnabled) {
            this.ensureBar(p);
        }

        if (this.actionbarEnabled && mode == HudService.HudMode.OFF) {
            this.actionbarShown.remove(id);
            this.lastActionbarText.remove(id);
            if (this.actionbarClearOnHide) {
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            }
        }

        this.savePlayers("bossbar.variable_players", this.variablePlayers);
        this.savePlayers("bossbar.off_players", this.offPlayers);
    }

    /**
     * 获取玩家当前显示模式（内存优先，其次持久化集合，最后回退默认模式）。
     */
    public HudMode getPlayerMode(Player p) {
        UUID id = p.getUniqueId();
        HudMode cachedMode = (HudMode) this.modes.get(id);
        if (cachedMode != null) {
            return cachedMode;
        } else if (this.offPlayers.contains(id)) {
            this.modes.put(id, HudService.HudMode.OFF);
            return HudService.HudMode.OFF;
        } else if (this.variablePlayers.contains(id)) {
            this.modes.put(id, HudService.HudMode.VARIABLE);
            return HudService.HudMode.VARIABLE;
        } else {
            return this.defaultMode;
        }
    }

    /**
     * 把玩家 UUID 集合保存到 hud.yml 的指定路径。
     */
    private void savePlayers(String path, Set<UUID> set) {
        List<String> out = new ArrayList();

        for (UUID uuid : set) {
            out.add(uuid.toString());
        }

        this.plugin.cfg.hud.set(path, out);
        this.plugin.saveConfig();
    }

    /**
     * 周期性刷新任务入口：遍历所有在线玩家刷新 HUD。
     */
    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.refreshPlayer(player);
        }

    }

    /**
     * 根据玩家所在世界的日历状态刷新其 BossBar/ActionBar 文本、进度与可见性。
     */
    public void refreshPlayer(Player p) {
        World playerWorld = p.getWorld();
        UUID playerId = p.getUniqueId();
        CalendarChannel channel = this.seasons.resolveChannel(playerWorld);
        if (!this.plugin.isWorldDisabled(playerWorld) && channel != null && this.seasons.isChannelEnabled(channel)) {
            CalendarState state = this.seasons.getStateCopy(playerWorld);
            int periodDays = Math.max(1, this.seasons.getCurrentPeriodLength(channel));
            long worldTime = playerWorld.getTime();
            BossBar bar = null;
            if (this.bossbarEnabled) {
                this.ensureBar(p);
                bar = (BossBar) this.bars.get(playerId);
                if (bar == null) {
                    return;
                }
            }

            HudMode mode = this.getPlayerMode(p);
            if (mode == HudService.HudMode.OFF) {
                if (this.bossbarEnabled) {
                    bar.setVisible(false);
                    bar.removePlayer(p);
                }

                this.actionbarShown.remove(playerId);
                this.lastActionbarText.remove(playerId);
            } else {
                if (this.bossbarEnabled && !bar.getPlayers().contains(p)) {
                    bar.addPlayer(p);
                }

                String seasonName = this.plugin.lang.tr(p, "season." + state.season.name());
                String monthText = "";
                if (state.monthsEnabled) {
                    String rawMonth = state.monthDisplayName != null && !state.monthDisplayName.isBlank() ? state.monthDisplayName : state.monthId;
                    if (rawMonth != null && !rawMonth.isBlank()) {
                        monthText = " &8| " + rawMonth;
                    }
                }

                String title;
                if (playerWorld != null && "aeternum_heat".equalsIgnoreCase(playerWorld.getName())) {
                    String realmName = this.plugin.lang.tr(p, "realm.heat_overworld");
                    String heatTitle = this.plugin.lang.trf(p, "hud.title_dim", Map.of("day", state.day, "year", state.year, "season", seasonName, "realm", realmName));
                    title = heatTitle + monthText;
                } else if (playerWorld != null && "aeternum_frost".equalsIgnoreCase(playerWorld.getName())) {
                    String realmName = this.plugin.lang.tr(p, "realm.frost_overworld");
                    String frostTitle = this.plugin.lang.trf(p, "hud.title_dim", Map.of("day", state.day, "year", state.year, "season", seasonName, "realm", realmName));
                    title = frostTitle + monthText;
                } else {
                    String baseTitle = this.plugin.lang.trf(p, "hud.title", Map.of("day", state.day, "year", state.year, "season", seasonName));
                    title = baseTitle + monthText;
                }

                String coloredTitle = this.color(title);
                double progress = Math.max((double) 0.0F, Math.min((double) 1.0F, (double) state.day / (double) periodDays));
                Season visualSeason = state.season;
                if (this.actionbarEnabled) {
                    if (mode == HudService.HudMode.FIXED) {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloredTitle));
                        this.lastActionbarText.put(playerId, coloredTitle);
                        this.actionbarShown.add(playerId);
                    } else {
                        boolean showNow = this.isHudTime(worldTime);
                        if (showNow) {
                            String last = (String) this.lastActionbarText.get(playerId);
                            boolean wasShown = this.actionbarShown.contains(playerId);
                            if (!wasShown || !Objects.equals(last, coloredTitle)) {
                                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloredTitle));
                                this.lastActionbarText.put(playerId, coloredTitle);
                                this.actionbarShown.add(playerId);
                            }
                        } else {
                            this.actionbarShown.remove(playerId);
                            this.lastActionbarText.remove(playerId);
                            if (this.actionbarClearOnHide) {
                                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                            }
                        }
                    }
                }

                if (this.bossbarEnabled) {
                    bar.setTitle(coloredTitle);
                    bar.setProgress(progress);
                    if (this.colorBySeason) {
                        BarColor barColor;
                        switch (visualSeason) {
                            case SPRING -> barColor = BarColor.GREEN;
                            case SUMMER -> barColor = BarColor.YELLOW;
                            case AUTUMN -> barColor = BarColor.RED;
                            case WINTER -> barColor = BarColor.BLUE;
                            default -> throw new MatchException((String) null, (Throwable) null);
                        }

                        bar.setColor(barColor);
                    }

                    if (mode == HudService.HudMode.FIXED) {
                        bar.setVisible(true);
                    } else {
                        bar.setVisible(this.isHudTime(worldTime));
                    }
                }

            }
        } else {
            this.cleanupBossBar(playerId);
            this.actionbarShown.remove(playerId);
            this.lastActionbarText.remove(playerId);
            HudMode currentMode = this.getPlayerMode(p);
            if (currentMode != HudService.HudMode.OFF) {
                this.modes.remove(playerId);
            }

        }
    }

    /**
     * 判断当前世界时间是否处于 HUD 需要显示的时段（清晨、正午、黄昏三个片段）。
     */
    private boolean isHudTime(long time) {
        long dayTime = time % 24000L;
        if (dayTime >= 0L && dayTime < 2000L) {
            return true;
        } else if (dayTime >= 6000L && dayTime < 8000L) {
            return true;
        } else {
            return dayTime >= 13000L && dayTime < 15000L;
        }
    }

    /**
     * 把 {@code &} 颜色代码转换为实际颜色。
     */
    private String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * HUD 显示模式：常驻、按时段显示、关闭。
     */
    public enum HudMode {
        FIXED,
        VARIABLE,
        OFF;

        HudMode() {
        }

        // $FF: synthetic method
        private static HudMode[] $values() {
            return new HudMode[]{FIXED, VARIABLE, OFF};
        }
    }
}
