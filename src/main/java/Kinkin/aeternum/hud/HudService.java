package Kinkin.aeternum.hud;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarChannel;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.util.PlayerArrivalSync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

public final class HudService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final Map<UUID, BossBar> bars = new HashMap();
    private final Map<UUID, HudMode> modes = new HashMap();
    private final Set<UUID> variablePlayers = new HashSet();
    private final Set<UUID> offPlayers = new HashSet();
    private BukkitTask task;
    private PlayerArrivalSync arrivalSync;
    private final boolean bossbarEnabled;
    private final boolean actionbarEnabled;
    private final boolean actionbarClearOnHide;
    private final boolean colorBySeason;
    private final long updateTicks;
    private final HudMode defaultMode;
    private final Map<UUID, String> lastActionbarText = new HashMap();
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

        HudMode dm;
        try {
            dm = HudService.HudMode.valueOf(rawDefault.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException var10) {
            dm = HudService.HudMode.FIXED;
        }

        this.defaultMode = dm;

        for(String s : plugin.cfg.hud.getStringList("bossbar.variable_players")) {
            try {
                this.variablePlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException var9) {
            }
        }

        for(String s : plugin.cfg.hud.getStringList("bossbar.off_players")) {
            try {
                this.offPlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException var8) {
            }
        }

    }

    private NamespacedKey barKey(UUID id) {
        String var10000 = id.toString().replace("-", "");
        String k = "hud_" + var10000.toLowerCase(Locale.ROOT);
        return new NamespacedKey(this.plugin, k);
    }

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

    private void cleanupBossBarsForOnlinePlayers() {
        for(Player p : Bukkit.getOnlinePlayers()) {
            this.cleanupBossBar(p.getUniqueId());
        }

    }

    public void register() {
        if (this.bossbarEnabled || this.actionbarEnabled) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            if (this.arrivalSync != null) {
                this.arrivalSync.close();
            }

            this.arrivalSync = new PlayerArrivalSync(this.plugin, this::resyncPlayer);
            this.cleanupBossBarsForOnlinePlayers();
            if (this.bossbarEnabled) {
                for(Player p : Bukkit.getOnlinePlayers()) {
                    this.ensureBar(p);
                }
            }

            if (this.task != null) {
                this.task.cancel();
            }

            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 1L, this.updateTicks);
        }
    }

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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (this.bossbarEnabled) {
            this.cleanupBossBar(e.getPlayer().getUniqueId());
            this.ensureBar(e.getPlayer());
        }

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        this.resyncPlayer(e.getPlayer());
    }

    private void resyncPlayer(Player player) {
        if (this.bossbarEnabled) {
            this.cleanupBossBar(player.getUniqueId());
        }

        this.lastActionbarText.remove(player.getUniqueId());
        this.actionbarShown.remove(player.getUniqueId());
        this.refreshPlayer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        this.cleanupBossBar(id);
        if (this.actionbarEnabled && this.actionbarClearOnHide) {
            e.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }

        this.lastActionbarText.remove(id);
        this.actionbarShown.remove(id);
        this.modes.remove(id);
    }

    private void ensureBar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = (BossBar)this.bars.get(id);
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

    public HudMode getPlayerMode(Player p) {
        UUID id = p.getUniqueId();
        HudMode mem = (HudMode)this.modes.get(id);
        if (mem != null) {
            return mem;
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

    private void savePlayers(String path, Set<UUID> set) {
        List<String> out = new ArrayList();

        for(UUID u : set) {
            out.add(u.toString());
        }

        this.plugin.cfg.hud.set(path, out);
        this.plugin.saveConfig();
    }

    public void run() {
        for(Player p : Bukkit.getOnlinePlayers()) {
            this.refreshPlayer(p);
        }

    }

    public void refreshPlayer(Player p) {
        World pw = p.getWorld();
        UUID pid = p.getUniqueId();
        CalendarChannel channel = this.seasons.resolveChannel(pw);
        if (!this.plugin.isWorldDisabled(pw) && channel != null && this.seasons.isChannelEnabled(channel)) {
            CalendarState s = this.seasons.getStateCopy(pw);
            int periodDays = Math.max(1, this.seasons.getCurrentPeriodLength(channel));
            long time = pw.getTime();
            BossBar bar = null;
            if (this.bossbarEnabled) {
                this.ensureBar(p);
                bar = (BossBar)this.bars.get(pid);
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

                this.actionbarShown.remove(pid);
                this.lastActionbarText.remove(pid);
            } else {
                if (this.bossbarEnabled && !bar.getPlayers().contains(p)) {
                    bar.addPlayer(p);
                }

                String seasonName = this.plugin.lang.tr(p, "season." + s.season.name());
                String monthText = "";
                if (s.monthsEnabled) {
                    String rawMonth = s.monthDisplayName != null && !s.monthDisplayName.isBlank() ? s.monthDisplayName : s.monthId;
                    if (rawMonth != null && !rawMonth.isBlank()) {
                        monthText = " &8| " + rawMonth;
                    }
                }

                String title;
                if (pw != null && pw.getName().equalsIgnoreCase("aeternum_heat")) {
                    String realmName = this.plugin.lang.tr(p, "realm.heat_overworld");
                    String var26 = this.plugin.lang.trf(p, "hud.title_dim", Map.of("day", s.day, "year", s.year, "season", seasonName, "realm", realmName));
                    title = var26 + monthText;
                } else if (pw != null && pw.getName().equalsIgnoreCase("aeternum_frost")) {
                    String realmName = this.plugin.lang.tr(p, "realm.frost_overworld");
                    String var25 = this.plugin.lang.trf(p, "hud.title_dim", Map.of("day", s.day, "year", s.year, "season", seasonName, "realm", realmName));
                    title = var25 + monthText;
                } else {
                    String var10000 = this.plugin.lang.trf(p, "hud.title", Map.of("day", s.day, "year", s.year, "season", seasonName));
                    title = var10000 + monthText;
                }

                String coloredTitle = this.color(title);
                double progress = Math.max((double)0.0F, Math.min((double)1.0F, (double)s.day / (double)periodDays));
                Season visualSeason = s.season;
                if (this.actionbarEnabled) {
                    if (mode == HudService.HudMode.FIXED) {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloredTitle));
                        this.lastActionbarText.put(pid, coloredTitle);
                        this.actionbarShown.add(pid);
                    } else {
                        boolean showNow = this.isHudTime(time);
                        if (showNow) {
                            String last = (String)this.lastActionbarText.get(pid);
                            boolean wasShown = this.actionbarShown.contains(pid);
                            if (!wasShown || !Objects.equals(last, coloredTitle)) {
                                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloredTitle));
                                this.lastActionbarText.put(pid, coloredTitle);
                                this.actionbarShown.add(pid);
                            }
                        } else {
                            this.actionbarShown.remove(pid);
                            this.lastActionbarText.remove(pid);
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
                        BarColor var10001;
                        switch (visualSeason) {
                            case SPRING -> var10001 = BarColor.GREEN;
                            case SUMMER -> var10001 = BarColor.YELLOW;
                            case AUTUMN -> var10001 = BarColor.RED;
                            case WINTER -> var10001 = BarColor.BLUE;
                            default -> throw new MatchException((String)null, (Throwable)null);
                        }

                        bar.setColor(var10001);
                    }

                    if (mode == HudService.HudMode.FIXED) {
                        bar.setVisible(true);
                    } else {
                        bar.setVisible(this.isHudTime(time));
                    }
                }

            }
        } else {
            this.cleanupBossBar(pid);
            this.actionbarShown.remove(pid);
            this.lastActionbarText.remove(pid);
            HudMode currentMode = this.getPlayerMode(p);
            if (currentMode != HudService.HudMode.OFF) {
                this.modes.remove(pid);
            }

        }
    }

    private boolean isHudTime(long time) {
        long t = time % 24000L;
        if (t >= 0L && t < 2000L) {
            return true;
        } else if (t >= 6000L && t < 8000L) {
            return true;
        } else {
            return t >= 13000L && t < 15000L;
        }
    }

    private String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static enum HudMode {
        FIXED,
        VARIABLE,
        OFF;

        private HudMode() {
        }

        // $FF: synthetic method
        private static HudMode[] $values() {
            return new HudMode[]{FIXED, VARIABLE, OFF};
        }
    }
}
