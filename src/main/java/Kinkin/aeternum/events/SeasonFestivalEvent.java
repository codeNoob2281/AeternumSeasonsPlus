package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class SeasonFestivalEvent implements SeasonalEvent, Listener {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private int maxPerSeason;
    private double merchantChance;
    private double headlinerChance;
    private int minFestivalVillagersNearPlayer;
    private boolean forceProfessionOnFestivalMerchants;
    private final Map<String, Integer> perSeasonCount = new HashMap();
    private int tickCounter = 0;
    private final NamespacedKey FESTIVAL_TRADE_KEY;
    private final NamespacedKey FESTIVAL_ROLE_KEY;
    private final NamespacedKey FESTIVAL_DAY_KEY;
    private final NamespacedKey FESTIVAL_SEASON_KEY;
    private final NamespacedKey FESTIVAL_YEAR_KEY;
    private final NamespacedKey FESTIVAL_NAME_SAVED;
    private final NamespacedKey FESTIVAL_ORIG_NAME;
    private final NamespacedKey FESTIVAL_ORIG_NAMEVIS;
    private final NamespacedKey FESTIVAL_ASSIGNED_NAME;
    private final NamespacedKey FESTIVAL_PROF_SAVED;
    private final NamespacedKey FESTIVAL_ORIG_PROF;
    private final NamespacedKey FESTIVAL_ORIG_LEVEL;
    private final NamespacedKey FESTIVAL_ORIG_XP;
    private boolean festivalActive = false;
    private CalendarState activeFestivalState = null;
    private final Map<UUID, Long> instantApplyCooldown = new HashMap();

    public SeasonFestivalEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.FESTIVAL_TRADE_KEY = new NamespacedKey(plugin, "festival_trade");
        this.FESTIVAL_ROLE_KEY = new NamespacedKey(plugin, "festival_role");
        this.FESTIVAL_DAY_KEY = new NamespacedKey(plugin, "festival_day");
        this.FESTIVAL_SEASON_KEY = new NamespacedKey(plugin, "festival_season");
        this.FESTIVAL_YEAR_KEY = new NamespacedKey(plugin, "festival_year");
        this.FESTIVAL_NAME_SAVED = new NamespacedKey(plugin, "festival_name_saved");
        this.FESTIVAL_ORIG_NAME = new NamespacedKey(plugin, "festival_orig_name");
        this.FESTIVAL_ORIG_NAMEVIS = new NamespacedKey(plugin, "festival_orig_namevis");
        this.FESTIVAL_ASSIGNED_NAME = new NamespacedKey(plugin, "festival_assigned_name");
        this.FESTIVAL_PROF_SAVED = new NamespacedKey(plugin, "festival_prof_saved");
        this.FESTIVAL_ORIG_PROF = new NamespacedKey(plugin, "festival_orig_prof");
        this.FESTIVAL_ORIG_LEVEL = new NamespacedKey(plugin, "festival_orig_level");
        this.FESTIVAL_ORIG_XP = new NamespacedKey(plugin, "festival_orig_xp");
        this.reloadFromConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.festival.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = y.getInt("events.festival.min_duration_days", 3);
        this.maxDur = y.getInt("events.festival.max_duration_days", 3);
        this.baseChance = y.getDouble("events.festival.base_chance_per_day", 0.1);
        this.maxPerSeason = y.getInt("events.festival.max_per_season", 1);
        this.merchantChance = y.getDouble("events.festival.merchant_chance", 0.22);
        this.headlinerChance = y.getDouble("events.festival.headliner_chance", 0.08);
        this.minFestivalVillagersNearPlayer = y.getInt("events.festival.min_merchants_near_player", 6);
        this.forceProfessionOnFestivalMerchants = y.getBoolean("events.festival.force_profession_on_merchants", false);
    }

    public String getId() {
        return "festival";
    }

    public String getDisplayName() {
        return "Seasonal Festival";
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

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else {
            int var10000 = st.year;
            String key = var10000 + "_" + st.season.name();
            int used = (Integer)this.perSeasonCount.getOrDefault(key, 0);
            if (used >= this.maxPerSeason) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        int var10000 = st.year;
        String key = var10000 + "_" + st.season.name();
        this.perSeasonCount.put(key, (Integer)this.perSeasonCount.getOrDefault(key, 0) + 1);
        this.festivalActive = true;
        this.activeFestivalState = st;
        this.instantApplyCooldown.clear();

        for(World w : ctx.overworlds()) {
            w.playSound(w.getSpawnLocation(), Sound.MUSIC_DISC_FAR, 1.0F, 1.0F);
        }

        for(Player p : Bukkit.getOnlinePlayers()) {
            String var10 = st.season.name();
            String tKey = "event.festival." + var10.toLowerCase(Locale.ROOT) + ".title";
            var10 = st.season.name();
            String sKey = "event.festival." + var10.toLowerCase(Locale.ROOT) + ".subtitle";
            p.sendTitle(this.plugin.lang.tr(p, tKey), this.plugin.lang.tr(p, sKey), 20, 80, 40);
        }

        this.applyFestivalToVillages(st);
    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.festivalActive = false;
        this.activeFestivalState = null;
        this.instantApplyCooldown.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.festival.end"));
        }

        for(World w : ctx.overworlds()) {
            for(Villager v : w.getEntitiesByClass(Villager.class)) {
                this.removeFestivalTrades(v);
                this.restoreOriginalNameIfNeeded(v.getPersistentDataContainer(), v);
                this.restoreOriginalProfessionIfNeeded(v.getPersistentDataContainer(), v);
                this.clearFestivalKeys(v.getPersistentDataContainer());
            }

            for(WanderingTrader t : w.getEntitiesByClass(WanderingTrader.class)) {
                this.removeFestivalTrades(t);
                this.clearFestivalKeys(t.getPersistentDataContainer());
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        this.activeFestivalState = st;
        if (this.globalParticlesEnabled) {
            for(World w : ctx.overworlds()) {
                for(Villager v : w.getEntitiesByClass(Villager.class)) {
                    Byte role = (Byte)v.getPersistentDataContainer().get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                    if (role != null && role != 0) {
                        w.spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add((double)0.0F, 1.8, (double)0.0F), role == 2 ? 10 : 6, 0.35, 0.45, 0.35, 0.01);
                    }
                }
            }
        }

        ++this.tickCounter;
        if (this.tickCounter >= 40) {
            this.tickCounter = 0;
            this.applyFestivalToVillages(st);
        }

    }

    private void applyFestivalToVillages(CalendarState st) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            this.applyFestivalNearPlayer(p, st);
        }

    }

    private void applyFestivalNearPlayer(Player p, CalendarState st) {
        if (p != null && p.isOnline()) {
            World w = p.getWorld();
            if (w.getEnvironment() == Environment.NORMAL) {
                long dayIndex = this.getWorldDayIndex(w);
                List<Villager> nearVillagers = new ArrayList();
                List<WanderingTrader> nearTraders = new ArrayList();

                for(Entity e : w.getNearbyEntities(p.getLocation(), (double)48.0F, (double)32.0F, (double)48.0F)) {
                    if (e.getType() == EntityType.VILLAGER) {
                        nearVillagers.add((Villager)e);
                    } else if (e.getType() == EntityType.WANDERING_TRADER) {
                        nearTraders.add((WanderingTrader)e);
                    }
                }

                if (!nearVillagers.isEmpty() || !nearTraders.isEmpty()) {
                    this.ensureMinimumFestivalMerchants(nearVillagers, st, dayIndex);

                    for(Villager v : nearVillagers) {
                        this.ensureFestivalVillager(v, st, dayIndex);
                    }

                    for(WanderingTrader t : nearTraders) {
                        this.ensureFestivalTrader(t, st, dayIndex);
                    }

                }
            }
        }
    }

    private long getWorldDayIndex(World w) {
        return w.getFullTime() / 24000L;
    }

    private boolean isPlayerProtectedVillager(Villager v, PersistentDataContainer pdc) {
        String cn = v.getCustomName();
        if (cn != null && !cn.isEmpty()) {
            String assigned = (String)pdc.get(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING);
            if (assigned == null || !assigned.equals(cn)) {
                return true;
            }
        }

        return false;
    }

    private void tryInstantFestivalApply(Player p) {
        if (this.festivalActive && this.activeFestivalState != null) {
            if (p != null && p.isOnline()) {
                if (p.getWorld().getEnvironment() == Environment.NORMAL) {
                    long now = System.currentTimeMillis();
                    long last = (Long)this.instantApplyCooldown.getOrDefault(p.getUniqueId(), 0L);
                    if (now - last >= 750L) {
                        this.instantApplyCooldown.put(p.getUniqueId(), now);
                        this.applyFestivalNearPlayer(p, this.activeFestivalState);
                    }
                }
            }
        }
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onPlayerJoinFestival(PlayerJoinEvent e) {
        this.tryInstantFestivalApply(e.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onPlayerTeleportFestival(PlayerTeleportEvent e) {
        this.tryInstantFestivalApply(e.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onPlayerChangedWorldFestival(PlayerChangedWorldEvent e) {
        this.tryInstantFestivalApply(e.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onPlayerMoveFestival(PlayerMoveEvent e) {
        if (e.getTo() != null) {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
                this.tryInstantFestivalApply(e.getPlayer());
            }
        }
    }

    private boolean isEligibleFestivalCandidate(Villager v) {
        if (v != null && !v.isDead()) {
            if (!v.isAdult()) {
                return false;
            } else {
                Villager.Profession prof = v.getProfession();
                if (prof != Profession.NONE && prof != Profession.NITWIT) {
                    return !this.isPlayerProtectedVillager(v, v.getPersistentDataContainer());
                } else {
                    return false;
                }
            }
        } else {
            return false;
        }
    }

    private void ensureMinimumFestivalMerchants(List<Villager> nearVillagers, CalendarState st, long dayIndex) {
        if (this.minFestivalVillagersNearPlayer > 0) {
            if (nearVillagers != null && !nearVillagers.isEmpty()) {
                String curSeason = st.season.name();
                int year = st.year;
                List<Villager> eligible = new ArrayList();
                int merchants = 0;
                int headliners = 0;

                for(Villager v : nearVillagers) {
                    if (this.isEligibleFestivalCandidate(v)) {
                        PersistentDataContainer pdc = v.getPersistentDataContainer();
                        Byte role = (Byte)pdc.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                        String ss = (String)pdc.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                        Integer yy = (Integer)pdc.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                        boolean sameEvent = ss != null && ss.equals(curSeason) && yy != null && yy == year;
                        if (role != null && role > 0 && sameEvent) {
                            ++merchants;
                            if (role == 2) {
                                ++headliners;
                            }
                        } else {
                            eligible.add(v);
                        }
                    }
                }

                int target = Math.min(this.minFestivalVillagersNearPlayer, merchants + eligible.size());
                if (merchants < target) {
                    eligible.sort(Comparator.comparing(Entity::getUniqueId));

                    for(Villager v : eligible) {
                        if (merchants >= target) {
                            break;
                        }

                        PersistentDataContainer pdc = v.getPersistentDataContainer();
                        pdc.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte)1);
                        pdc.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, curSeason);
                        pdc.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, year);
                        ++merchants;
                    }

                    if (merchants > 0 && headliners == 0) {
                        Villager pick = null;

                        for(Villager v : nearVillagers) {
                            if (this.isEligibleFestivalCandidate(v)) {
                                PersistentDataContainer pdc = v.getPersistentDataContainer();
                                Byte role = (Byte)pdc.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                                String ss = (String)pdc.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                                Integer yy = (Integer)pdc.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                                if (role != null && role == 1 && ss != null && ss.equals(curSeason) && yy != null && yy == year) {
                                    pick = v;
                                    break;
                                }
                            }
                        }

                        if (pick != null) {
                            pick.getPersistentDataContainer().set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte)2);
                        }
                    }

                }
            }
        }
    }

    private byte computeRole(Villager v, Season season, int year) {
        if (!v.isAdult()) {
            return 0;
        } else if (this.isPlayerProtectedVillager(v, v.getPersistentDataContainer())) {
            return 0;
        } else {
            Villager.Profession prof = v.getProfession();
            if (prof != Profession.NONE && prof != Profession.NITWIT) {
                long seed = v.getUniqueId().getMostSignificantBits() ^ v.getUniqueId().getLeastSignificantBits() ^ (long)season.name().hashCode() << 1 ^ (long)year * 1315423911L;
                Random r = new Random(seed);
                return r.nextDouble() > this.merchantChance ? 0 : (byte)(r.nextDouble() < this.headlinerChance ? 2 : 1);
            } else {
                return 0;
            }
        }
    }

    private void ensureFestivalVillager(Villager v, CalendarState st, long dayIndex) {
        if (v != null && !v.isDead()) {
            PersistentDataContainer pdc = v.getPersistentDataContainer();
            if (this.isPlayerProtectedVillager(v, pdc)) {
                this.removeFestivalTrades(v);
                String assigned = (String)pdc.get(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING);
                String cur = v.getCustomName();
                if (assigned != null && cur != null && assigned.equals(cur)) {
                    this.restoreOriginalNameIfNeeded(pdc, v);
                }

                this.restoreOriginalProfessionIfNeeded(pdc, v);
                this.clearFestivalKeys(pdc);
            } else {
                String curSeason = st.season.name();
                String savedSeason = (String)pdc.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                Integer savedYear = (Integer)pdc.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                boolean mismatch = savedSeason != null && (!savedSeason.equals(curSeason) || savedYear != null && savedYear != st.year);
                if (mismatch) {
                    this.removeFestivalTrades(v);
                    this.restoreOriginalNameIfNeeded(pdc, v);
                    this.restoreOriginalProfessionIfNeeded(pdc, v);
                    this.clearFestivalKeys(pdc);
                }

                Byte role = (Byte)pdc.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                if (role == null) {
                    role = this.computeRole(v, st.season, st.year);
                    pdc.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, role);
                    pdc.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, curSeason);
                    pdc.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, st.year);
                }

                Villager.Profession currentProfession = v.getProfession();
                if (currentProfession == Profession.NONE || currentProfession == Profession.NITWIT) {
                    role = 0;
                    pdc.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte)0);
                }

                if (role == 0) {
                    this.removeFestivalTrades(v);
                    this.restoreOriginalNameIfNeeded(pdc, v);
                    this.restoreOriginalProfessionIfNeeded(pdc, v);
                    this.clearFestivalKeys(pdc);
                } else {
                    if (this.forceProfessionOnFestivalMerchants) {
                        this.ensureFestivalProfession(pdc, v, st, dayIndex, role == 2);
                    }

                    this.ensureFestivalName(pdc, v, st.season, role == 2);
                    Long lastDay = (Long)pdc.get(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG);
                    if (lastDay == null || lastDay != dayIndex) {
                        this.removeFestivalTrades(v);
                        List<MerchantRecipe> newFestival = this.buildDynamicFestivalTrades(st.season, st.year, dayIndex, v.getUniqueId(), role == 2);
                        this.appendFestivalTrades(v, newFestival);
                        pdc.set(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG, dayIndex);
                    }
                }
            }
        }
    }

    private void ensureFestivalTrader(WanderingTrader t, CalendarState st, long dayIndex) {
        if (t != null && !t.isDead()) {
            PersistentDataContainer pdc = t.getPersistentDataContainer();
            pdc.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte)2);
            pdc.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, st.season.name());
            pdc.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, st.year);
            Long lastDay = (Long)pdc.get(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG);
            if (lastDay == null || lastDay != dayIndex) {
                this.removeFestivalTrades(t);
                List<MerchantRecipe> offers = this.buildDynamicFestivalTrades(st.season, st.year, dayIndex, t.getUniqueId(), true);
                offers = offers.subList(0, Math.min(3, offers.size()));
                this.appendFestivalTrades(t, offers);
                pdc.set(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG, dayIndex);
            }
        }
    }

    private void ensureFestivalProfession(PersistentDataContainer pdc, Villager v, CalendarState st, long dayIndex, boolean headliner) {
        Villager.Profession prof = v.getProfession();
        if (prof == Profession.NONE || prof == Profession.NITWIT) {
            if (!pdc.has(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE)) {
                pdc.set(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE, (byte)1);
                pdc.set(this.FESTIVAL_ORIG_PROF, PersistentDataType.STRING, prof.name());

                try {
                    pdc.set(this.FESTIVAL_ORIG_LEVEL, PersistentDataType.INTEGER, v.getVillagerLevel());
                } catch (Throwable var14) {
                }

                try {
                    pdc.set(this.FESTIVAL_ORIG_XP, PersistentDataType.INTEGER, v.getVillagerExperience());
                } catch (Throwable var13) {
                }
            }

            Villager.Profession newProf = this.pickFestivalProfession(v.getUniqueId(), st, headliner);

            try {
                v.setProfession(newProf);
            } catch (Throwable var12) {
            }

            try {
                v.setVillagerLevel(2);
            } catch (Throwable var11) {
            }

            try {
                v.setVillagerExperience(10);
            } catch (Throwable var10) {
            }

        }
    }

    private Villager.Profession pickFestivalProfession(UUID id, CalendarState st, boolean headliner) {
        List<Villager.Profession> pool = new ArrayList(Arrays.asList(Profession.FARMER, Profession.LIBRARIAN, Profession.CLERIC, Profession.TOOLSMITH, Profession.ARMORER, Profession.WEAPONSMITH, Profession.BUTCHER, Profession.FISHERMAN, Profession.FLETCHER, Profession.CARTOGRAPHER, Profession.MASON, Profession.SHEPHERD, Profession.LEATHERWORKER));
        long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ (long)st.season.name().hashCode() * 31L ^ (long)st.year * 131L ^ (headliner ? 777L : 0L);
        Random r = new Random(seed);
        return (Villager.Profession)pool.get(r.nextInt(pool.size()));
    }

    private void restoreOriginalProfessionIfNeeded(PersistentDataContainer pdc, Villager v) {
        if (pdc.has(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE)) {
            String orig = (String)pdc.get(this.FESTIVAL_ORIG_PROF, PersistentDataType.STRING);
            if (orig != null) {
                try {
                    Villager.Profession p = Profession.valueOf(orig);
                    v.setProfession(p);
                } catch (Throwable var9) {
                }
            }

            Integer lvl = (Integer)pdc.get(this.FESTIVAL_ORIG_LEVEL, PersistentDataType.INTEGER);
            Integer xp = (Integer)pdc.get(this.FESTIVAL_ORIG_XP, PersistentDataType.INTEGER);
            if (lvl != null) {
                try {
                    v.setVillagerLevel(lvl);
                } catch (Throwable var8) {
                }
            }

            if (xp != null) {
                try {
                    v.setVillagerExperience(xp);
                } catch (Throwable var7) {
                }
            }

        }
    }

    private void ensureFestivalName(PersistentDataContainer pdc, Villager v, Season season, boolean headliner) {
        if (!pdc.has(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE)) {
            pdc.set(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE, (byte)1);
            String orig = v.getCustomName();
            pdc.set(this.FESTIVAL_ORIG_NAME, PersistentDataType.STRING, orig == null ? "" : orig);
            pdc.set(this.FESTIVAL_ORIG_NAMEVIS, PersistentDataType.BYTE, (byte)(v.isCustomNameVisible() ? 1 : 0));
        }

        String var10000;
        switch (season) {
            case SPRING -> var10000 = String.valueOf(ChatColor.GREEN) + "✿";
            case SUMMER -> var10000 = String.valueOf(ChatColor.GOLD) + "☀";
            case AUTUMN -> var10000 = String.valueOf(ChatColor.GOLD) + "\ud83c\udf42";
            case WINTER -> var10000 = String.valueOf(ChatColor.AQUA) + "❄";
            default -> var10000 = String.valueOf(ChatColor.YELLOW) + "★";
        }

        String icon = var10000;
        String traderName = this.plugin.lang.trServer("event.festival.trader_name");
        String seasonName = this.plugin.lang.trServer("season." + season.name());
        String name = icon + " " + (headliner ? String.valueOf(ChatColor.GOLD) + "★ " : "") + traderName + String.valueOf(ChatColor.GRAY) + " (" + seasonName + ")";
        v.setCustomName(name);
        v.setCustomNameVisible(true);
        pdc.set(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING, name);
    }

    private void restoreOriginalNameIfNeeded(PersistentDataContainer pdc, Villager v) {
        if (pdc.has(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE)) {
            String orig = (String)pdc.get(this.FESTIVAL_ORIG_NAME, PersistentDataType.STRING);
            Byte vis = (Byte)pdc.get(this.FESTIVAL_ORIG_NAMEVIS, PersistentDataType.BYTE);
            if (orig != null && !orig.isEmpty()) {
                v.setCustomName(orig);
            } else {
                v.setCustomName((String)null);
            }

            if (vis != null) {
                v.setCustomNameVisible(vis == 1);
            }

        }
    }

    private int themeOfDay(long dayIndex) {
        return Math.floorMod(dayIndex, 4);
    }

    private List<MerchantRecipe> buildDynamicFestivalTrades(Season season, int year, long dayIndex, UUID owner, boolean headliner) {
        int theme = this.themeOfDay(dayIndex);
        long seed = owner.getMostSignificantBits() ^ owner.getLeastSignificantBits() ^ (long)season.name().hashCode() << 2 ^ (long)year * 214013L ^ dayIndex * 2531011L ^ (long)theme * 97L;
        Random r = new Random(seed);
        List<Offer> headliners = this.buildHeadlinerPool(season);
        List<Offer> themed = this.buildThemePool(season, theme);
        List<Offer> commons = this.buildCommonPool(season);
        List<MerchantRecipe> out = new ArrayList();
        out.add(this.toRecipe(this.pickWeighted(headliners, r), r, "HEADLINER"));

        for(Offer o : this.pickManyWeighted(themed, 2, r)) {
            out.add(this.toRecipe(o, r, "DAILY"));
        }

        for(Offer o : this.pickManyWeighted(commons, headliner ? 3 : 2, r)) {
            out.add(this.toRecipe(o, r, "MARKET"));
        }

        out.add(this.toRecipeWithDiscount(this.pickWeighted(commons, r), r, "DISCOUNT", 0.2));
        Collections.shuffle(out, r);
        int max = headliner ? 7 : 6;
        if (out.size() > max) {
            out = new ArrayList(out.subList(0, max));
        }

        return out;
    }

    private Offer pickWeighted(List<Offer> pool, Random r) {
        int total = 0;

        for(Offer o : pool) {
            total += Math.max(1, o.weight);
        }

        int roll = r.nextInt(Math.max(1, total));
        int acc = 0;

        for(Offer o : pool) {
            acc += Math.max(1, o.weight);
            if (roll < acc) {
                return o;
            }
        }

        return (Offer)pool.get(Math.max(0, pool.size() - 1));
    }

    private List<Offer> pickManyWeighted(List<Offer> pool, int count, Random r) {
        List<Offer> copy = new ArrayList(pool);
        List<Offer> out = new ArrayList();
        count = Math.min(count, copy.size());

        for(int i = 0; i < count; ++i) {
            Offer picked = this.pickWeighted(copy, r);
            out.add(picked);
            copy.remove(picked);
        }

        return out;
    }

    private MerchantRecipe toRecipe(Offer o, Random r, String kind) {
        int emeralds = this.randBetween(r, o.emeraldMin, o.emeraldMax);
        ItemStack result = this.markAsFestival(o.builder.build(r), kind);
        MerchantRecipe mr = new MerchantRecipe(result, Math.max(1, o.maxUses));
        mr.addIngredient(new ItemStack(Material.EMERALD, Math.max(1, emeralds)));
        if (o.extraMat != null) {
            int extra = this.randBetween(r, o.extraMin, o.extraMax);
            if (extra > 0) {
                mr.addIngredient(new ItemStack(o.extraMat, extra));
            }
        }

        mr.setVillagerExperience(0);
        return mr;
    }

    private MerchantRecipe toRecipeWithDiscount(Offer o, Random r, String kind, double pctOff) {
        int base = this.randBetween(r, o.emeraldMin, o.emeraldMax);
        int disc = (int)Math.max((double)1.0F, Math.floor((double)base * ((double)1.0F - pctOff)));
        ItemStack result = this.markAsFestival(o.builder.build(r), kind + "_SALE");
        MerchantRecipe mr = new MerchantRecipe(result, Math.max(1, o.maxUses));
        mr.addIngredient(new ItemStack(Material.EMERALD, disc));
        if (o.extraMat != null) {
            int extra = this.randBetween(r, o.extraMin, o.extraMax);
            if (extra > 0) {
                mr.addIngredient(new ItemStack(o.extraMat, extra));
            }
        }

        mr.setVillagerExperience(0);
        return mr;
    }

    private int randBetween(Random r, int min, int max) {
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }

        return min == max ? min : min + r.nextInt(max - min + 1);
    }

    private void appendFestivalTrades(Villager v, List<MerchantRecipe> festival) {
        List<MerchantRecipe> base = new ArrayList();

        for(MerchantRecipe rec : v.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                base.add(rec);
            }
        }

        base.addAll(festival);
        v.setRecipes(base);
    }

    private void appendFestivalTrades(WanderingTrader t, List<MerchantRecipe> festival) {
        List<MerchantRecipe> base = new ArrayList();

        for(MerchantRecipe rec : t.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                base.add(rec);
            }
        }

        base.addAll(festival);
        t.setRecipes(base);
    }

    private void removeFestivalTrades(Villager v) {
        List<MerchantRecipe> remaining = new ArrayList();

        for(MerchantRecipe rec : v.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                remaining.add(rec);
            }
        }

        v.setRecipes(remaining);
    }

    private void removeFestivalTrades(WanderingTrader t) {
        List<MerchantRecipe> remaining = new ArrayList();

        for(MerchantRecipe rec : t.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                remaining.add(rec);
            }
        }

        t.setRecipes(remaining);
    }

    private ItemStack markAsFestival(ItemStack it, String kind) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            meta.getPersistentDataContainer().set(this.FESTIVAL_TRADE_KEY, PersistentDataType.BYTE, (byte)1);
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList();
            }

            lore.add(String.valueOf(ChatColor.DARK_GRAY) + "Seasonal Festival");
            if (kind != null) {
                String var10001 = String.valueOf(ChatColor.GRAY);
                lore.add(var10001 + "Type: " + kind);
            }

            lore.add(String.valueOf(ChatColor.DARK_GRAY) + "Stock rotates daily");
            meta.setLore(lore);
            it.setItemMeta(meta);
            return it;
        }
    }

    private boolean isFestivalTrade(MerchantRecipe r) {
        ItemStack result = r.getResult();
        if (result == null) {
            return false;
        } else {
            ItemMeta meta = result.getItemMeta();
            return meta == null ? false : meta.getPersistentDataContainer().has(this.FESTIVAL_TRADE_KEY, PersistentDataType.BYTE);
        }
    }

    private void clearFestivalKeys(PersistentDataContainer pdc) {
        pdc.remove(this.FESTIVAL_ROLE_KEY);
        pdc.remove(this.FESTIVAL_DAY_KEY);
        pdc.remove(this.FESTIVAL_SEASON_KEY);
        pdc.remove(this.FESTIVAL_YEAR_KEY);
        pdc.remove(this.FESTIVAL_NAME_SAVED);
        pdc.remove(this.FESTIVAL_ORIG_NAME);
        pdc.remove(this.FESTIVAL_ORIG_NAMEVIS);
        pdc.remove(this.FESTIVAL_ASSIGNED_NAME);
        pdc.remove(this.FESTIVAL_PROF_SAVED);
        pdc.remove(this.FESTIVAL_ORIG_PROF);
        pdc.remove(this.FESTIVAL_ORIG_LEVEL);
        pdc.remove(this.FESTIVAL_ORIG_XP);
    }

    private Enchantment ench(String key) {
        try {
            NamespacedKey k = NamespacedKey.minecraft(key);
            Enchantment e = null;

            try {
                e = (Enchantment)Registry.ENCHANTMENT.get(k);
            } catch (Throwable var5) {
            }

            if (e != null) {
                return e;
            }

            try {
                return Enchantment.getByKey(k);
            } catch (Throwable var6) {
            }
        } catch (Throwable var7) {
        }

        return null;
    }

    private Map<Enchantment, int[]> enchRanges(Object... args) {
        Map<Enchantment, int[]> m = new LinkedHashMap();

        for(int i = 0; i + 1 < args.length; i += 2) {
            String key = (String)args[i];
            int[] range = (int[])args[i + 1];
            Enchantment e = this.ench(key);
            if (e != null) {
                m.put(e, range);
            }
        }

        return m;
    }

    private List<Offer> buildHeadlinerPool(Season season) {
        List<Offer> list = new ArrayList();
        list.add(new Offer(6, 1, 48, 64, Material.DIAMOND, 1, 2, (rr) -> {
            Material tmpl = this.pickTrimTemplate(rr, season);
            if (tmpl == null) {
                tmpl = Material.DIAMOND;
            }

            ItemStack it = new ItemStack(tmpl, 1);
            String var10002 = String.valueOf(ChatColor.GOLD) + "Festival Headliner";
            String[] var10003 = new String[]{String.valueOf(ChatColor.GRAY) + "Stock rotates daily", null};
            String var10006 = String.valueOf(ChatColor.DARK_GRAY);
            var10003[1] = var10006 + "Season: " + season.name();
            return this.tagItem(it, var10002, Arrays.asList(var10003));
        }));
        list.add(new Offer(5, 1, 24, 34, Material.AMETHYST_SHARD, 4, 8, (rr) -> this.makeEnchantedTool(rr, season, Material.DIAMOND_PICKAXE, String.valueOf(ChatColor.AQUA) + "Festival Pickaxe", this.enchRanges("efficiency", new int[]{3, 5}, "unbreaking", new int[]{2, 3}, "fortune", new int[]{1, 3}, "silk_touch", new int[]{1, 1}), 2)));
        list.add(new Offer(5, 1, 40, 58, Material.DIAMOND, 1, 1, (rr) -> this.makeEnchantedTool(rr, season, Material.DIAMOND_SWORD, String.valueOf(ChatColor.LIGHT_PURPLE) + "Festival Blade", this.enchRanges("sharpness", new int[]{3, 5}, "unbreaking", new int[]{2, 3}, "looting", new int[]{1, 3}, "fire_aspect", new int[]{1, 2}), 2)));
        list.add(new Offer(6, 1, 24, 36, Material.EMERALD_BLOCK, 1, 1, (rr) -> this.makeEnchantedBook(rr, season, String.valueOf(ChatColor.GREEN) + "Festival Tome", this.enchRanges("efficiency", new int[]{4, 5}, "unbreaking", new int[]{3, 3}, "protection", new int[]{3, 4}, "sharpness", new int[]{4, 5}, "fortune", new int[]{2, 3}, "looting", new int[]{2, 3}), 2)));
        if (season == Season.WINTER) {
            list.add(new Offer(4, 1, 42, 64, Material.BLAZE_ROD, 2, 4, (rr) -> {
                Material m = this.matchMaterial("NETHERITE_UPGRADE_SMITHING_TEMPLATE");
                if (m == null) {
                    m = Material.NETHERITE_SCRAP;
                }

                ItemStack it = new ItemStack(m, 1);
                return this.tagItem(it, String.valueOf(ChatColor.GOLD) + "Winter Legendary Upgrade", Arrays.asList(String.valueOf(ChatColor.GRAY) + "One-time offer", String.valueOf(ChatColor.DARK_GRAY) + "Festival only"));
            }));
        }

        return list;
    }

    private List<Offer> buildThemePool(Season season, int theme) {
        List<Offer> list = new ArrayList();
        if (theme == 0) {
            list.add(new Offer(10, 4, 6, 12, (Material)null, 0, 0, (rr) -> this.tagItem(new ItemStack(Material.BONE_MEAL, 32), String.valueOf(ChatColor.GREEN) + "Farmer's Boost", List.of(String.valueOf(ChatColor.GRAY) + "Seasonal deal"))));
            Material torch = this.matchMaterial("TORCHFLOWER_SEEDS");
            if (torch != null) {
                list.add(new Offer(6, 2, 18, 30, Material.EMERALD, 0, 0, (rr) -> this.tagItem(new ItemStack(torch, 1), String.valueOf(ChatColor.YELLOW) + "Rare Seeds: Torchflower", List.of(String.valueOf(ChatColor.GRAY) + "Hard to find in the wild"))));
            }

            Material pitcher = this.matchMaterial("PITCHER_POD");
            if (pitcher != null) {
                list.add(new Offer(6, 2, 18, 30, Material.EMERALD, 0, 0, (rr) -> this.tagItem(new ItemStack(pitcher, 1), String.valueOf(ChatColor.YELLOW) + "Rare Seeds: Pitcher", List.of(String.valueOf(ChatColor.GRAY) + "Hard to find in the wild"))));
            }

            list.add(new Offer(8, 6, 4, 10, (Material)null, 0, 0, (rr) -> new ItemStack(Material.MELON_SEEDS, 24)));
            list.add(new Offer(8, 6, 4, 10, (Material)null, 0, 0, (rr) -> new ItemStack(Material.PUMPKIN_SEEDS, 24)));
            list.add(new Offer(6, 2, 10, 18, (Material)null, 0, 0, (rr) -> new ItemStack(Material.BEE_NEST, 1)));
        }

        if (theme == 1) {
            list.add(new Offer(10, 8, 5, 10, (Material)null, 0, 0, (rr) -> new ItemStack(Material.IRON_INGOT, 16)));
            list.add(new Offer(8, 6, 7, 12, (Material)null, 0, 0, (rr) -> new ItemStack(Material.GOLD_INGOT, 10)));
            list.add(new Offer(10, 2, 12, 20, (Material)null, 0, 0, (rr) -> new ItemStack(Material.AMETHYST_SHARD, 24)));
            list.add(new Offer(10, 2, 12, 20, Material.DIAMOND, 1, 1, (rr) -> new ItemStack(Material.NETHERITE_SCRAP, 1)));
            list.add(new Offer(8, 2, 9, 15, (Material)null, 0, 0, (rr) -> new ItemStack(Material.QUARTZ, 32)));
        }

        if (theme == 2) {
            list.add(new Offer(10, 2, 18, 30, (Material)null, 0, 0, (rr) -> this.makeEnchantedBook(rr, season, String.valueOf(ChatColor.AQUA) + "Enchanter's Pick", this.enchRanges("efficiency", new int[]{2, 4}, "unbreaking", new int[]{2, 3}, "fortune", new int[]{1, 3}, "silk_touch", new int[]{1, 1}), 1)));
            list.add(new Offer(10, 2, 18, 30, (Material)null, 0, 0, (rr) -> this.makeEnchantedBook(rr, season, String.valueOf(ChatColor.LIGHT_PURPLE) + "Combat Manual", this.enchRanges("sharpness", new int[]{2, 4}, "unbreaking", new int[]{2, 3}, "fire_aspect", new int[]{1, 2}, "looting", new int[]{1, 3}), 1)));
            list.add(new Offer(8, 2, 16, 26, (Material)null, 0, 0, (rr) -> this.makeEnchantedTool(rr, season, Material.IRON_PICKAXE, String.valueOf(ChatColor.YELLOW) + "Lucky Pick", this.enchRanges("efficiency", new int[]{2, 3}, "unbreaking", new int[]{1, 2}), 2)));
        }

        if (theme == 3) {
            list.add(new Offer(10, 3, 14, 22, (Material)null, 0, 0, (rr) -> new ItemStack(Material.NAME_TAG, 1)));
            list.add(new Offer(9, 2, 16, 24, (Material)null, 0, 0, (rr) -> new ItemStack(Material.SADDLE, 1)));
            list.add(new Offer(8, 4, 10, 18, (Material)null, 0, 0, (rr) -> new ItemStack(Material.LEAD, 4)));
            list.add(new Offer(8, 6, 8, 14, (Material)null, 0, 0, (rr) -> new ItemStack(Material.FIREWORK_ROCKET, 24)));
            list.add(new Offer(7, 3, 12, 20, (Material)null, 0, 0, (rr) -> new ItemStack(Material.GLOW_INK_SAC, 8)));
            list.add(new Offer(6, 2, 18, 30, (Material)null, 0, 0, (rr) -> this.pickMusicDisc(rr)));
        }

        return list;
    }

    private List<Offer> buildCommonPool(Season season) {
        List<Offer> list = new ArrayList();
        list.add(new Offer(10, 6, 6, 12, (Material)null, 0, 0, (rr) -> new ItemStack(Material.BREAD, 16)));
        list.add(new Offer(10, 6, 6, 12, (Material)null, 0, 0, (rr) -> new ItemStack(Material.COOKED_BEEF, 12)));
        list.add(new Offer(9, 6, 8, 14, (Material)null, 0, 0, (rr) -> new ItemStack(Material.EXPERIENCE_BOTTLE, 8)));
        list.add(new Offer(8, 4, 10, 18, (Material)null, 0, 0, (rr) -> new ItemStack(Material.LAPIS_LAZULI, 24)));
        list.add(new Offer(9, 5, 5, 9, (Material)null, 0, 0, (rr) -> new ItemStack(Material.IRON_INGOT, 8)));
        list.add(new Offer(8, 4, 6, 11, (Material)null, 0, 0, (rr) -> new ItemStack(Material.GOLD_INGOT, 6)));
        list.add(new Offer(8, 2, 10, 18, (Material)null, 0, 0, (rr) -> this.makeEnchantedBook(rr, season, String.valueOf(ChatColor.AQUA) + "Mystery Enchant", this.enchRanges("unbreaking", new int[]{1, 3}, "efficiency", new int[]{1, 4}, "protection", new int[]{1, 4}, "sharpness", new int[]{1, 4}, "power", new int[]{1, 4}), 1)));
        return list;
    }

    private ItemStack makeEnchantedBook(Random r, Season season, String title, Map<Enchantment, int[]> pool, int enchCount) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta base = book.getItemMeta();
        if (base instanceof EnchantmentStorageMeta meta) {
            List<Enchantment> keys = new ArrayList(pool.keySet());
            Collections.shuffle(keys, r);
            int picks = Math.max(1, Math.min(enchCount, keys.size()));

            for(int i = 0; i < picks; ++i) {
                Enchantment e = (Enchantment)keys.get(i);
                int[] range = (int[])pool.get(e);
                int lvl = range == null ? 1 : this.randBetween(r, range[0], range[1]);
                meta.addStoredEnchant(e, lvl, true);
            }

            meta.setDisplayName(title);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            List<String> lore = new ArrayList();
            String var10001 = String.valueOf(ChatColor.GRAY);
            lore.add(var10001 + "Season: " + season.name());
            lore.add(String.valueOf(ChatColor.DARK_GRAY) + "Limited festival stock");
            meta.setLore(lore);
            book.setItemMeta(meta);
            return book;
        } else {
            return book;
        }
    }

    private ItemStack makeEnchantedTool(Random r, Season season, Material baseMat, String title, Map<Enchantment, int[]> pool, int enchCount) {
        ItemStack it = new ItemStack(baseMat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            List<Enchantment> keys = new ArrayList(pool.keySet());
            Collections.shuffle(keys, r);
            int picks = Math.max(1, Math.min(enchCount, keys.size()));

            for(int i = 0; i < picks; ++i) {
                Enchantment e = (Enchantment)keys.get(i);
                int[] range = (int[])pool.get(e);
                int lvl = range == null ? 1 : this.randBetween(r, range[0], range[1]);
                it.addUnsafeEnchantment(e, lvl);
            }

            meta.setDisplayName(title);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            List<String> lore = new ArrayList();
            String var10001 = String.valueOf(ChatColor.GRAY);
            lore.add(var10001 + "Season: " + season.name());
            lore.add(String.valueOf(ChatColor.DARK_GRAY) + "Festival crafted");
            meta.setLore(lore);
            it.setItemMeta(meta);
            return it;
        }
    }

    private ItemStack tagItem(ItemStack it, String name, List<String> loreExtra) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList();
            if (loreExtra != null) {
                lore.addAll(loreExtra);
            }

            meta.setLore(lore);
            it.setItemMeta(meta);
            return it;
        }
    }

    private ItemStack pickMusicDisc(Random r) {
        Material[] pool = new Material[]{Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD, Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_11, Material.MUSIC_DISC_WAIT};
        return new ItemStack(pool[r.nextInt(pool.length)], 1);
    }

    private Material pickTrimTemplate(Random r, Season season) {
        List<String> names = new ArrayList(Arrays.asList("SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE", "DUNE_ARMOR_TRIM_SMITHING_TEMPLATE", "COAST_ARMOR_TRIM_SMITHING_TEMPLATE", "WILD_ARMOR_TRIM_SMITHING_TEMPLATE", "WARD_ARMOR_TRIM_SMITHING_TEMPLATE", "VEX_ARMOR_TRIM_SMITHING_TEMPLATE", "TIDE_ARMOR_TRIM_SMITHING_TEMPLATE", "SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE", "RIB_ARMOR_TRIM_SMITHING_TEMPLATE", "EYE_ARMOR_TRIM_SMITHING_TEMPLATE", "SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE", "SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE", "WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE", "RAISER_ARMOR_TRIM_SMITHING_TEMPLATE", "SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE", "HOST_ARMOR_TRIM_SMITHING_TEMPLATE"));
        Collections.shuffle(names, r);

        for(String n : names) {
            Material m = this.matchMaterial(n);
            if (m != null) {
                return m;
            }
        }

        return null;
    }

    private Material matchMaterial(String name) {
        try {
            return Material.matchMaterial(name);
        } catch (Throwable var3) {
            return null;
        }
    }

    @EventHandler(
        priority = EventPriority.NORMAL,
        ignoreCancelled = true
    )
    public void onInteractMerchant(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Villager || e.getRightClicked() instanceof WanderingTrader) {
            this.tryInstantFestivalApply(e.getPlayer());
            PersistentDataContainer pdc = e.getRightClicked().getPersistentDataContainer();
            Byte role = (Byte)pdc.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
            String season = (String)pdc.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
            if (role != null && role != 0 && season != null) {
                String seasonName = this.plugin.lang.tr(e.getPlayer(), "season." + season);
                String msg = this.plugin.lang.trOr(e.getPlayer(), "event.festival.actionbar", "&6Festival Market&7 • &e{season}&8 • &7Stock changes daily").replace("{season}", seasonName);
                this.sendActionBarSafe(e.getPlayer(), msg);
            }
        }
    }

    private void sendActionBarSafe(Player p, String msg) {
        try {
            Method m = p.getClass().getMethod("sendActionBar", String.class);
            m.invoke(p, msg);
        } catch (Throwable var4) {
        }

    }

    private static final class Offer {
        final int weight;
        final int maxUses;
        final int emeraldMin;
        final int emeraldMax;
        final Material extraMat;
        final int extraMin;
        final int extraMax;
        final ResultBuilder builder;

        Offer(int weight, int maxUses, int emeraldMin, int emeraldMax, Material extraMat, int extraMin, int extraMax, ResultBuilder builder) {
            super();
            this.weight = weight;
            this.maxUses = maxUses;
            this.emeraldMin = emeraldMin;
            this.emeraldMax = emeraldMax;
            this.extraMat = extraMat;
            this.extraMin = extraMin;
            this.extraMax = extraMax;
            this.builder = builder;
        }
    }

    @FunctionalInterface
    private interface ResultBuilder {
        ItemStack build(Random var1);
    }
}
