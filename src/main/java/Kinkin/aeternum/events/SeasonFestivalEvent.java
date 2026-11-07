package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 季节集市事件（Seasonal Festival）。
 *
 * <p>事件激活期间，主世界玩家附近的村民与流浪商人会被“布置”成节日商贩：
 * <ul>
 *     <li>村民会获得节日名称、可选的固定职业，并按“主题日”刷新一套节日交易；
 *     <li>流浪商人作为头牌商贩（headliner）提供 3 个节日交易；
 *     <li>交易内容根据季节、年份、日期与商贩 UUID 确定性生成（每日轮换）；</li>
 *     <li>玩家靠近/加入/传送/换世界/移动时立即尝试刷新其附近商贩，且带冷却。</li>
 * </ul>
 * 事件结束时移除所有节日交易、恢复商贩原始名称与职业，并清理持久化标记。
 * 通过 {@link PersistentDataContainer} 记录状态，配置来自 {@link YamlEvents}
 * 读取的 {@code events.yml}。</p>
 */
public final class SeasonFestivalEvent implements SeasonalEvent, Listener {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 是否启用全局粒子效果。
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
     * 每季节最多触发次数。
     */
    private int maxPerSeason;
    /**
     * 村民被选为节日商贩的概率。
     */
    private double merchantChance;
    /**
     * 节日商贩成为头牌（headliner）的概率。
     */
    private double headlinerChance;
    /**
     * 玩家附近需要保证的最少节日商贩数。
     */
    private int minFestivalVillagersNearPlayer;
    /**
     * 是否强制给节日商贩设置固定职业。
     */
    private boolean forceProfessionOnFestivalMerchants;
    /**
     * 记录每个“年份_季节”已触发次数。
     */
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
    /**
     * 玩家 UUID 到最近一次立即刷新的时间戳，用于限流。
     */
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

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.festival.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = config.getInt("events.festival.min_duration_days", 3);
        this.maxDur = config.getInt("events.festival.max_duration_days", 3);
        this.baseChance = config.getDouble("events.festival.base_chance_per_day", 0.1);
        this.maxPerSeason = config.getInt("events.festival.max_per_season", 1);
        this.merchantChance = config.getDouble("events.festival.merchant_chance", 0.22);
        this.headlinerChance = config.getDouble("events.festival.headliner_chance", 0.08);
        this.minFestivalVillagersNearPlayer = config.getInt("events.festival.min_merchants_near_player", 6);
        this.forceProfessionOnFestivalMerchants = config.getBoolean("events.festival.force_profession_on_merchants", false);
    }

    @Override
    public String getId() {
        return "festival";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.festival.display_name", "&6Seasonal Festival");
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
     * 仅当本季节触发次数未超上限、且通过概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            int year = state.year;
            String key = year + "_" + state.season.name();
            int used = (Integer) this.perSeasonCount.getOrDefault(key, 0);
            if (used >= this.maxPerSeason) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始时累加本季触发次数、播放音乐、发送按季节区分的标题并布置附近商贩。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        int year = state.year;
        String key = year + "_" + state.season.name();
        this.perSeasonCount.put(key, (Integer) this.perSeasonCount.getOrDefault(key, 0) + 1);
        this.festivalActive = true;
        this.activeFestivalState = state;
        this.instantApplyCooldown.clear();

        for (World world : context.overworlds()) {
            world.playSound(world.getSpawnLocation(), Sound.MUSIC_DISC_FAR, 1.0F, 1.0F);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            String seasonName = state.season.name();
            String titleKey = "event.festival." + seasonName.toLowerCase(Locale.ROOT) + ".title";
            seasonName = state.season.name();
            String subtitleKey = "event.festival." + seasonName.toLowerCase(Locale.ROOT) + ".subtitle";
            player.sendTitle(this.plugin.lang.tr(player, titleKey), this.plugin.lang.tr(player, subtitleKey), 20, 80, 40);
        }

        this.applyFestivalToVillages(state);
    }

    /**
     * 事件结束时清理状态、通知玩家，并移除/还原所有主世界商贩的节日改动。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.festivalActive = false;
        this.activeFestivalState = null;
        this.instantApplyCooldown.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.festival.end"));
        }

        for (World world : context.overworlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                this.removeFestivalTrades(villager);
                this.restoreOriginalNameIfNeeded(villager.getPersistentDataContainer(), villager);
                this.restoreOriginalProfessionIfNeeded(villager.getPersistentDataContainer(), villager);
                this.clearFestivalKeys(villager.getPersistentDataContainer());
            }

            for (WanderingTrader trader : world.getEntitiesByClass(WanderingTrader.class)) {
                this.removeFestivalTrades(trader);
                this.clearFestivalKeys(trader.getPersistentDataContainer());
            }
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    /**
     * 每个 tick 渲染商贩粒子，并每隔 40 tick 刷新一次附近商贩。
     */
    @Override
    public void onTick(CalendarState state, EventContext context) {
        this.activeFestivalState = state;
        if (this.globalParticlesEnabled) {
            for (World world : context.overworlds()) {
                for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                    Byte role = (Byte) villager.getPersistentDataContainer().get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                    if (role != null && role != 0) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, villager.getLocation().add((double) 0.0F, 1.8, (double) 0.0F), role == 2 ? 10 : 6, 0.35, 0.45, 0.35, 0.01);
                    }
                }
            }
        }

        ++this.tickCounter;
        if (this.tickCounter >= 40) {
            this.tickCounter = 0;
            this.applyFestivalToVillages(state);
        }

    }

    /**
     * 对所有在线玩家刷新其附近的节日商贩。
     */
    private void applyFestivalToVillages(CalendarState state) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.applyFestivalNearPlayer(player, state);
        }

    }

    /**
     * 刷新单个玩家附近 48×32×48 范围内的村民与流浪商人。
     */
    private void applyFestivalNearPlayer(Player player, CalendarState state) {
        if (player != null && player.isOnline()) {
            World world = player.getWorld();
            if (world.getEnvironment() == Environment.NORMAL) {
                long dayIndex = this.getWorldDayIndex(world);
                List<Villager> nearVillagers = new ArrayList();
                List<WanderingTrader> nearTraders = new ArrayList();

                for (Entity entity : world.getNearbyEntities(player.getLocation(), (double) 48.0F, (double) 32.0F, (double) 48.0F)) {
                    if (entity.getType() == EntityType.VILLAGER) {
                        nearVillagers.add((Villager) entity);
                    } else if (entity.getType() == EntityType.WANDERING_TRADER) {
                        nearTraders.add((WanderingTrader) entity);
                    }
                }

                if (!nearVillagers.isEmpty() || !nearTraders.isEmpty()) {
                    this.ensureMinimumFestivalMerchants(nearVillagers, state, dayIndex);

                    for (Villager villager : nearVillagers) {
                        this.ensureFestivalVillager(villager, state, dayIndex);
                    }

                    for (WanderingTrader trader : nearTraders) {
                        this.ensureFestivalTrader(trader, state, dayIndex);
                    }

                }
            }
        }
    }

    /**
     * 计算世界内的天数索引（每 24000 tick 为一天）。
     */
    private long getWorldDayIndex(World world) {
        return world.getFullTime() / 24000L;
    }

    /**
     * 判断村民是否被玩家自定义命名（命名后不再被节日逻辑接管）。
     */
    private boolean isPlayerProtectedVillager(Villager villager, PersistentDataContainer container) {
        String customName = villager.getCustomName();
        if (customName != null && !customName.isEmpty()) {
            String assigned = (String) container.get(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING);
            return assigned == null || !assigned.equals(customName);
        }

        return false;
    }

    /**
     * 对单个玩家尝试立即刷新附近商贩（带 750ms 冷却）。
     */
    private void tryInstantFestivalApply(Player player) {
        if (this.festivalActive && this.activeFestivalState != null) {
            if (player != null && player.isOnline()) {
                if (player.getWorld().getEnvironment() == Environment.NORMAL) {
                    long now = System.currentTimeMillis();
                    long last = (Long) this.instantApplyCooldown.getOrDefault(player.getUniqueId(), 0L);
                    if (now - last >= 750L) {
                        this.instantApplyCooldown.put(player.getUniqueId(), now);
                        this.applyFestivalNearPlayer(player, this.activeFestivalState);
                    }
                }
            }
        }
    }

    /**
     * 监听玩家加入：立即尝试刷新其附近商贩。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerJoinFestival(PlayerJoinEvent event) {
        this.tryInstantFestivalApply(event.getPlayer());
    }

    /**
     * 监听玩家传送：立即尝试刷新其附近商贩。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerTeleportFestival(PlayerTeleportEvent event) {
        this.tryInstantFestivalApply(event.getPlayer());
    }

    /**
     * 监听玩家切换世界：立即尝试刷新其附近商贩。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerChangedWorldFestival(PlayerChangedWorldEvent event) {
        this.tryInstantFestivalApply(event.getPlayer());
    }

    /**
     * 监听玩家移动：仅当跨方块移动时才尝试刷新附近商贩。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerMoveFestival(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                this.tryInstantFestivalApply(event.getPlayer());
            }
        }
    }

    /**
     * 判断村民是否为合格的节日商贩候选（成年、有正式职业、未被玩家命名）。
     */
    private boolean isEligibleFestivalCandidate(Villager villager) {
        if (villager != null && !villager.isDead()) {
            if (!villager.isAdult()) {
                return false;
            } else {
                Villager.Profession profession = villager.getProfession();
                if (profession != Profession.NONE && profession != Profession.NITWIT) {
                    return !this.isPlayerProtectedVillager(villager, villager.getPersistentDataContainer());
                } else {
                    return false;
                }
            }
        } else {
            return false;
        }
    }

    /**
     * 确保玩家附近有足够数量（至少 minFestivalVillagersNearPlayer）的节日商贩。
     */
    private void ensureMinimumFestivalMerchants(List<Villager> nearVillagers, CalendarState state, long dayIndex) {
        if (this.minFestivalVillagersNearPlayer > 0) {
            if (nearVillagers != null && !nearVillagers.isEmpty()) {
                String curSeason = state.season.name();
                int year = state.year;
                List<Villager> eligible = new ArrayList();
                int merchants = 0;
                int headliners = 0;

                for (Villager villager : nearVillagers) {
                    if (this.isEligibleFestivalCandidate(villager)) {
                        PersistentDataContainer container = villager.getPersistentDataContainer();
                        Byte role = (Byte) container.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                        String savedSeason = (String) container.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                        Integer savedYear = (Integer) container.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                        boolean sameEvent = savedSeason != null && savedSeason.equals(curSeason) && savedYear != null && savedYear == year;
                        if (role != null && role > 0 && sameEvent) {
                            ++merchants;
                            if (role == 2) {
                                ++headliners;
                            }
                        } else {
                            eligible.add(villager);
                        }
                    }
                }

                int target = Math.min(this.minFestivalVillagersNearPlayer, merchants + eligible.size());
                if (merchants < target) {
                    eligible.sort(Comparator.comparing(Entity::getUniqueId));

                    for (Villager villager : eligible) {
                        if (merchants >= target) {
                            break;
                        }

                        PersistentDataContainer container = villager.getPersistentDataContainer();
                        container.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte) 1);
                        container.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, curSeason);
                        container.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, year);
                        ++merchants;
                    }

                    if (merchants > 0 && headliners == 0) {
                        Villager pick = null;

                        for (Villager villager : nearVillagers) {
                            if (this.isEligibleFestivalCandidate(villager)) {
                                PersistentDataContainer container = villager.getPersistentDataContainer();
                                Byte role = (Byte) container.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                                String savedSeason = (String) container.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                                Integer savedYear = (Integer) container.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                                if (role != null && role == 1 && savedSeason != null && savedSeason.equals(curSeason) && savedYear != null && savedYear == year) {
                                    pick = villager;
                                    break;
                                }
                            }
                        }

                        if (pick != null) {
                            pick.getPersistentDataContainer().set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte) 2);
                        }
                    }

                }
            }
        }
    }

    /**
     * 依据村民 UUID、季节与年份确定性计算其节日角色（0=非商贩，1=商贩，2=头牌）。
     */
    private byte computeRole(Villager villager, Season season, int year) {
        if (!villager.isAdult()) {
            return 0;
        } else if (this.isPlayerProtectedVillager(villager, villager.getPersistentDataContainer())) {
            return 0;
        } else {
            Villager.Profession profession = villager.getProfession();
            if (profession != Profession.NONE && profession != Profession.NITWIT) {
                long seed = villager.getUniqueId().getMostSignificantBits() ^ villager.getUniqueId().getLeastSignificantBits() ^ (long) season.name().hashCode() << 1 ^ (long) year * 1315423911L;
                Random random = new Random(seed);
                return random.nextDouble() > this.merchantChance ? 0 : (byte) (random.nextDouble() < this.headlinerChance ? 2 : 1);
            } else {
                return 0;
            }
        }
    }

    /**
     * 维护单个村民的节日状态：处理命名/职业恢复、角色分配、名称与交易刷新。
     */
    private void ensureFestivalVillager(Villager villager, CalendarState state, long dayIndex) {
        if (villager != null && !villager.isDead()) {
            PersistentDataContainer container = villager.getPersistentDataContainer();
            if (this.isPlayerProtectedVillager(villager, container)) {
                this.removeFestivalTrades(villager);
                String assigned = (String) container.get(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING);
                String current = villager.getCustomName();
                if (assigned != null && current != null && assigned.equals(current)) {
                    this.restoreOriginalNameIfNeeded(container, villager);
                }

                this.restoreOriginalProfessionIfNeeded(container, villager);
                this.clearFestivalKeys(container);
            } else {
                String curSeason = state.season.name();
                String savedSeason = (String) container.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
                Integer savedYear = (Integer) container.get(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER);
                boolean mismatch = savedSeason != null && (!savedSeason.equals(curSeason) || savedYear != null && savedYear != state.year);
                if (mismatch) {
                    this.removeFestivalTrades(villager);
                    this.restoreOriginalNameIfNeeded(container, villager);
                    this.restoreOriginalProfessionIfNeeded(container, villager);
                    this.clearFestivalKeys(container);
                }

                Byte role = (Byte) container.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
                if (role == null) {
                    role = this.computeRole(villager, state.season, state.year);
                    container.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, role);
                    container.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, curSeason);
                    container.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, state.year);
                }

                Villager.Profession currentProfession = villager.getProfession();
                if (currentProfession == Profession.NONE || currentProfession == Profession.NITWIT) {
                    role = 0;
                    container.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte) 0);
                }

                if (role == 0) {
                    this.removeFestivalTrades(villager);
                    this.restoreOriginalNameIfNeeded(container, villager);
                    this.restoreOriginalProfessionIfNeeded(container, villager);
                    this.clearFestivalKeys(container);
                } else {
                    if (this.forceProfessionOnFestivalMerchants) {
                        this.ensureFestivalProfession(container, villager, state, dayIndex, role == 2);
                    }

                    this.ensureFestivalName(container, villager, state.season, role == 2);
                    Long lastDay = (Long) container.get(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG);
                    if (lastDay == null || lastDay != dayIndex) {
                        this.removeFestivalTrades(villager);
                        List<MerchantRecipe> newFestival = this.buildDynamicFestivalTrades(state.season, state.year, dayIndex, villager.getUniqueId(), role == 2);
                        this.appendFestivalTrades(villager, newFestival);
                        container.set(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG, dayIndex);
                    }
                }
            }
        }
    }

    /**
     * 维护单个流浪商人的节日状态：将其作为头牌商贩并刷新至多 3 个节日交易。
     */
    private void ensureFestivalTrader(WanderingTrader trader, CalendarState state, long dayIndex) {
        if (trader != null && !trader.isDead()) {
            PersistentDataContainer container = trader.getPersistentDataContainer();
            container.set(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE, (byte) 2);
            container.set(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING, state.season.name());
            container.set(this.FESTIVAL_YEAR_KEY, PersistentDataType.INTEGER, state.year);
            Long lastDay = (Long) container.get(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG);
            if (lastDay == null || lastDay != dayIndex) {
                this.removeFestivalTrades(trader);
                List<MerchantRecipe> offers = this.buildDynamicFestivalTrades(state.season, state.year, dayIndex, trader.getUniqueId(), true);
                offers = offers.subList(0, Math.min(3, offers.size()));
                this.appendFestivalTrades(trader, offers);
                container.set(this.FESTIVAL_DAY_KEY, PersistentDataType.LONG, dayIndex);
            }
        }
    }

    /**
     * 为节日商贩设置固定职业（如原职业为无业/傻子），并记录原始职业、等级与经验以便恢复。
     */
    private void ensureFestivalProfession(PersistentDataContainer container, Villager villager, CalendarState state, long dayIndex, boolean headliner) {
        Villager.Profession profession = villager.getProfession();
        if (profession == Profession.NONE || profession == Profession.NITWIT) {
            if (!container.has(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE)) {
                container.set(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE, (byte) 1);
                container.set(this.FESTIVAL_ORIG_PROF, PersistentDataType.STRING, profession.name());

                try {
                    container.set(this.FESTIVAL_ORIG_LEVEL, PersistentDataType.INTEGER, villager.getVillagerLevel());
                } catch (Throwable ignored) {
                }

                try {
                    container.set(this.FESTIVAL_ORIG_XP, PersistentDataType.INTEGER, villager.getVillagerExperience());
                } catch (Throwable ignored) {
                }
            }

            Villager.Profession newProfession = this.pickFestivalProfession(villager.getUniqueId(), state, headliner);

            try {
                villager.setProfession(newProfession);
            } catch (Throwable ignored) {
            }

            try {
                villager.setVillagerLevel(2);
            } catch (Throwable ignored) {
            }

            try {
                villager.setVillagerExperience(10);
            } catch (Throwable ignored) {
            }

        }
    }

    /**
     * 依据村民 UUID、季节与年份确定性地挑选一个节日职业。
     */
    private Villager.Profession pickFestivalProfession(UUID id, CalendarState state, boolean headliner) {
        List<Villager.Profession> pool = new ArrayList(Arrays.asList(Profession.FARMER, Profession.LIBRARIAN, Profession.CLERIC, Profession.TOOLSMITH, Profession.ARMORER, Profession.WEAPONSMITH, Profession.BUTCHER, Profession.FISHERMAN, Profession.FLETCHER, Profession.CARTOGRAPHER, Profession.MASON, Profession.SHEPHERD, Profession.LEATHERWORKER));
        long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ (long) state.season.name().hashCode() * 31L ^ (long) state.year * 131L ^ (headliner ? 777L : 0L);
        Random random = new Random(seed);
        return (Villager.Profession) pool.get(random.nextInt(pool.size()));
    }

    /**
     * 若记录过原始职业，则恢复村民的职业、等级与经验。
     */
    private void restoreOriginalProfessionIfNeeded(PersistentDataContainer container, Villager villager) {
        if (container.has(this.FESTIVAL_PROF_SAVED, PersistentDataType.BYTE)) {
            String orig = (String) container.get(this.FESTIVAL_ORIG_PROF, PersistentDataType.STRING);
            if (orig != null) {
                try {
                    Villager.Profession profession = Profession.valueOf(orig);
                    villager.setProfession(profession);
                } catch (Throwable ignored) {
                }
            }

            Integer level = (Integer) container.get(this.FESTIVAL_ORIG_LEVEL, PersistentDataType.INTEGER);
            Integer xp = (Integer) container.get(this.FESTIVAL_ORIG_XP, PersistentDataType.INTEGER);
            if (level != null) {
                try {
                    villager.setVillagerLevel(level);
                } catch (Throwable ignored) {
                }
            }

            if (xp != null) {
                try {
                    villager.setVillagerExperience(xp);
                } catch (Throwable ignored) {
                }
            }

        }
    }

    /**
     * 为村民设置节日名称（按季节图标区分，头牌额外带星标），并记录原始名称与可见性。
     */
    private void ensureFestivalName(PersistentDataContainer container, Villager villager, Season season, boolean headliner) {
        if (!container.has(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE)) {
            container.set(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE, (byte) 1);
            String originalName = villager.getCustomName();
            container.set(this.FESTIVAL_ORIG_NAME, PersistentDataType.STRING, originalName == null ? "" : originalName);
            container.set(this.FESTIVAL_ORIG_NAMEVIS, PersistentDataType.BYTE, (byte) (villager.isCustomNameVisible() ? 1 : 0));
        }

        String iconColor;
        switch (season) {
            case SPRING -> iconColor = ChatColor.GREEN + "✿";
            case SUMMER -> iconColor = ChatColor.GOLD + "☀";
            case AUTUMN -> iconColor = ChatColor.GOLD + "\ud83c\udf42";
            case WINTER -> iconColor = ChatColor.AQUA + "❄";
            default -> iconColor = ChatColor.YELLOW + "★";
        }

        String icon = iconColor;
        String traderName = this.plugin.lang.trServer("event.festival.trader_name");
        String seasonName = this.plugin.lang.trServer("season." + season.name());
        String name = icon + " " + (headliner ? ChatColor.GOLD + "★ " : "") + traderName + ChatColor.GRAY + " (" + seasonName + ")";
        villager.setCustomName(name);
        villager.setCustomNameVisible(true);
        container.set(this.FESTIVAL_ASSIGNED_NAME, PersistentDataType.STRING, name);
    }

    /**
     * 若记录过原始名称，则恢复村民的名称与可见性。
     */
    private void restoreOriginalNameIfNeeded(PersistentDataContainer container, Villager villager) {
        if (container.has(this.FESTIVAL_NAME_SAVED, PersistentDataType.BYTE)) {
            String orig = (String) container.get(this.FESTIVAL_ORIG_NAME, PersistentDataType.STRING);
            Byte vis = (Byte) container.get(this.FESTIVAL_ORIG_NAMEVIS, PersistentDataType.BYTE);
            if (orig != null && !orig.isEmpty()) {
                villager.setCustomName(orig);
            } else {
                villager.setCustomName((String) null);
            }

            if (vis != null) {
                villager.setCustomNameVisible(vis == 1);
            }

        }
    }

    /**
     * 计算某天对应的主题索引（0~3 循环）。
     */
    private int themeOfDay(long dayIndex) {
        return Math.floorMod(dayIndex, 4);
    }

    /**
     * 依据季节、年份、日期、商贩 UUID 与主题日确定性地生成整套节日交易。
     */
    private List<MerchantRecipe> buildDynamicFestivalTrades(Season season, int year, long dayIndex, UUID owner, boolean headliner) {
        int theme = this.themeOfDay(dayIndex);
        long seed = owner.getMostSignificantBits() ^ owner.getLeastSignificantBits() ^ (long) season.name().hashCode() << 2 ^ (long) year * 214013L ^ dayIndex * 2531011L ^ (long) theme * 97L;
        Random random = new Random(seed);
        List<Offer> headliners = this.buildHeadlinerPool(season);
        List<Offer> themed = this.buildThemePool(season, theme);
        List<Offer> commons = this.buildCommonPool(season);
        List<MerchantRecipe> out = new ArrayList();
        out.add(this.toRecipe(this.pickWeighted(headliners, random), random, "HEADLINER"));

        for (Offer offer : this.pickManyWeighted(themed, 2, random)) {
            out.add(this.toRecipe(offer, random, "DAILY"));
        }

        for (Offer offer : this.pickManyWeighted(commons, headliner ? 3 : 2, random)) {
            out.add(this.toRecipe(offer, random, "MARKET"));
        }

        out.add(this.toRecipeWithDiscount(this.pickWeighted(commons, random), random, "DISCOUNT", 0.2));
        Collections.shuffle(out, random);
        int max = headliner ? 7 : 6;
        if (out.size() > max) {
            out = new ArrayList(out.subList(0, max));
        }

        return out;
    }

    /**
     * 按权重从候选池中随机挑选一个 Offer。
     */
    private Offer pickWeighted(List<Offer> pool, Random random) {
        int total = 0;

        for (Offer offer : pool) {
            total += Math.max(1, offer.weight);
        }

        int roll = random.nextInt(Math.max(1, total));
        int acc = 0;

        for (Offer offer : pool) {
            acc += Math.max(1, offer.weight);
            if (roll < acc) {
                return offer;
            }
        }

        return (Offer) pool.get(Math.max(0, pool.size() - 1));
    }

    /**
     * 按权重不放回地抽取最多 count 个 Offer。
     */
    private List<Offer> pickManyWeighted(List<Offer> pool, int count, Random random) {
        List<Offer> copy = new ArrayList(pool);
        List<Offer> out = new ArrayList();
        count = Math.min(count, copy.size());

        for (int i = 0; i < count; ++i) {
            Offer picked = this.pickWeighted(copy, random);
            out.add(picked);
            copy.remove(picked);
        }

        return out;
    }

    /**
     * 把 Offer 转换为一条 MerchantRecipe（绿宝石数量在区间内随机）。
     */
    private MerchantRecipe toRecipe(Offer offer, Random random, String kind) {
        int emeralds = this.randBetween(random, offer.emeraldMin, offer.emeraldMax);
        ItemStack result = this.markAsFestival(offer.builder.build(random), kind);
        MerchantRecipe recipe = new MerchantRecipe(result, Math.max(1, offer.maxUses));
        recipe.addIngredient(new ItemStack(Material.EMERALD, Math.max(1, emeralds)));
        if (offer.extraMat != null) {
            int extra = this.randBetween(random, offer.extraMin, offer.extraMax);
            if (extra > 0) {
                recipe.addIngredient(new ItemStack(offer.extraMat, extra));
            }
        }

        recipe.setVillagerExperience(0);
        return recipe;
    }

    /**
     * 把 Offer 转换为一条带折扣的 MerchantRecipe（绿宝石价格按比例下浮）。
     */
    private MerchantRecipe toRecipeWithDiscount(Offer offer, Random random, String kind, double pctOff) {
        int base = this.randBetween(random, offer.emeraldMin, offer.emeraldMax);
        int discounted = (int) Math.max((double) 1.0F, Math.floor((double) base * ((double) 1.0F - pctOff)));
        ItemStack result = this.markAsFestival(offer.builder.build(random), kind + "_SALE");
        MerchantRecipe recipe = new MerchantRecipe(result, Math.max(1, offer.maxUses));
        recipe.addIngredient(new ItemStack(Material.EMERALD, discounted));
        if (offer.extraMat != null) {
            int extra = this.randBetween(random, offer.extraMin, offer.extraMax);
            if (extra > 0) {
                recipe.addIngredient(new ItemStack(offer.extraMat, extra));
            }
        }

        recipe.setVillagerExperience(0);
        return recipe;
    }

    /**
     * 在 [min, max] 闭区间内随机取值（自动纠正 min/max 顺序）。
     */
    private int randBetween(Random random, int min, int max) {
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }

        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    /**
     * 把节日交易追加到村民原有（非节日）交易之后。
     */
    private void appendFestivalTrades(Villager villager, List<MerchantRecipe> festival) {
        List<MerchantRecipe> base = new ArrayList();

        for (MerchantRecipe rec : villager.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                base.add(rec);
            }
        }

        base.addAll(festival);
        villager.setRecipes(base);
    }

    /**
     * 把节日交易追加到流浪商人原有（非节日）交易之后。
     */
    private void appendFestivalTrades(WanderingTrader trader, List<MerchantRecipe> festival) {
        List<MerchantRecipe> base = new ArrayList();

        for (MerchantRecipe rec : trader.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                base.add(rec);
            }
        }

        base.addAll(festival);
        trader.setRecipes(base);
    }

    /**
     * 移除村民身上的所有节日交易，保留原有交易。
     */
    private void removeFestivalTrades(Villager villager) {
        List<MerchantRecipe> remaining = new ArrayList();

        for (MerchantRecipe rec : villager.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                remaining.add(rec);
            }
        }

        villager.setRecipes(remaining);
    }

    /**
     * 移除流浪商人身上的所有节日交易，保留原有交易。
     */
    private void removeFestivalTrades(WanderingTrader trader) {
        List<MerchantRecipe> remaining = new ArrayList();

        for (MerchantRecipe rec : trader.getRecipes()) {
            if (!this.isFestivalTrade(rec)) {
                remaining.add(rec);
            }
        }

        trader.setRecipes(remaining);
    }

    /**
     * 给物品打上“节日交易”标记并追加说明 lore，返回同一物品。
     */
    private ItemStack markAsFestival(ItemStack item, String kind) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            meta.getPersistentDataContainer().set(this.FESTIVAL_TRADE_KEY, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList();
            }

            lore.add(ChatColor.DARK_GRAY + "Seasonal Festival");
            if (kind != null) {
                String grayPrefix = String.valueOf(ChatColor.GRAY);
                lore.add(grayPrefix + "Type: " + kind);
            }

            lore.add(ChatColor.DARK_GRAY + "Stock rotates daily");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
    }

    /**
     * 判断交易结果是否带有“节日交易”标记。
     */
    private boolean isFestivalTrade(MerchantRecipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null) {
            return false;
        } else {
            ItemMeta meta = result.getItemMeta();
            return meta != null && meta.getPersistentDataContainer().has(this.FESTIVAL_TRADE_KEY, PersistentDataType.BYTE);
        }
    }

    /**
     * 清除村民/商人持久化容器中的全部节日相关键。
     */
    private void clearFestivalKeys(PersistentDataContainer container) {
        container.remove(this.FESTIVAL_ROLE_KEY);
        container.remove(this.FESTIVAL_DAY_KEY);
        container.remove(this.FESTIVAL_SEASON_KEY);
        container.remove(this.FESTIVAL_YEAR_KEY);
        container.remove(this.FESTIVAL_NAME_SAVED);
        container.remove(this.FESTIVAL_ORIG_NAME);
        container.remove(this.FESTIVAL_ORIG_NAMEVIS);
        container.remove(this.FESTIVAL_ASSIGNED_NAME);
        container.remove(this.FESTIVAL_PROF_SAVED);
        container.remove(this.FESTIVAL_ORIG_PROF);
        container.remove(this.FESTIVAL_ORIG_LEVEL);
        container.remove(this.FESTIVAL_ORIG_XP);
    }

    /**
     * 按原版附魔 key 兼容性地获取附魔对象（兼容多种注册表访问方式）。
     */
    private Enchantment ench(String key) {
        try {
            NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
            Enchantment enchantment = null;

            try {
                enchantment = (Enchantment) Registry.ENCHANTMENT.get(namespacedKey);
            } catch (Throwable ignored) {
            }

            if (enchantment != null) {
                return enchantment;
            }

            try {
                return Enchantment.getByKey(namespacedKey);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 把若干“附魔 key + 等级区间”参数对转换为附魔到区间数组的映射。
     */
    private Map<Enchantment, int[]> enchRanges(Object... args) {
        Map<Enchantment, int[]> result = new LinkedHashMap();

        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = (String) args[i];
            int[] range = (int[]) args[i + 1];
            Enchantment enchantment = this.ench(key);
            if (enchantment != null) {
                result.put(enchantment, range);
            }
        }

        return result;
    }

    /**
     * 构建头牌商贩的候选交易池。
     */
    private List<Offer> buildHeadlinerPool(Season season) {
        List<Offer> list = new ArrayList();
        list.add(new Offer(6, 1, 48, 64, Material.DIAMOND, 1, 2, (random) -> {
            Material template = this.pickTrimTemplate(random, season);
            if (template == null) {
                template = Material.DIAMOND;
            }

            ItemStack item = new ItemStack(template, 1);
            String headlinerTitle = ChatColor.GOLD + "Festival Headliner";
            String[] loreLines = new String[]{ChatColor.GRAY + "Stock rotates daily", null};
            String darkGrayPrefix = String.valueOf(ChatColor.DARK_GRAY);
            loreLines[1] = darkGrayPrefix + "Season: " + season.name();
            return this.tagItem(item, headlinerTitle, Arrays.asList(loreLines));
        }));
        list.add(new Offer(5, 1, 24, 34, Material.AMETHYST_SHARD, 4, 8, (random) -> this.makeEnchantedTool(random, season, Material.DIAMOND_PICKAXE, ChatColor.AQUA + "Festival Pickaxe", this.enchRanges("efficiency", new int[]{3, 5}, "unbreaking", new int[]{2, 3}, "fortune", new int[]{1, 3}, "silk_touch", new int[]{1, 1}), 2)));
        list.add(new Offer(5, 1, 40, 58, Material.DIAMOND, 1, 1, (random) -> this.makeEnchantedTool(random, season, Material.DIAMOND_SWORD, ChatColor.LIGHT_PURPLE + "Festival Blade", this.enchRanges("sharpness", new int[]{3, 5}, "unbreaking", new int[]{2, 3}, "looting", new int[]{1, 3}, "fire_aspect", new int[]{1, 2}), 2)));
        list.add(new Offer(6, 1, 24, 36, Material.EMERALD_BLOCK, 1, 1, (random) -> this.makeEnchantedBook(random, season, ChatColor.GREEN + "Festival Tome", this.enchRanges("efficiency", new int[]{4, 5}, "unbreaking", new int[]{3, 3}, "protection", new int[]{3, 4}, "sharpness", new int[]{4, 5}, "fortune", new int[]{2, 3}, "looting", new int[]{2, 3}), 2)));
        if (season == Season.WINTER) {
            list.add(new Offer(4, 1, 42, 64, Material.BLAZE_ROD, 2, 4, (random) -> {
                Material material = this.matchMaterial("NETHERITE_UPGRADE_SMITHING_TEMPLATE");
                if (material == null) {
                    material = Material.NETHERITE_SCRAP;
                }

                ItemStack item = new ItemStack(material, 1);
                return this.tagItem(item, ChatColor.GOLD + "Winter Legendary Upgrade", Arrays.asList(ChatColor.GRAY + "One-time offer", ChatColor.DARK_GRAY + "Festival only"));
            }));
        }

        return list;
    }

    /**
     * 构建按主题日（0~3）划分的节日交易池。
     */
    private List<Offer> buildThemePool(Season season, int theme) {
        List<Offer> list = new ArrayList();
        if (theme == 0) {
            list.add(new Offer(10, 4, 6, 12, (Material) null, 0, 0, (random) -> this.tagItem(new ItemStack(Material.BONE_MEAL, 32), ChatColor.GREEN + "Farmer's Boost", List.of(ChatColor.GRAY + "Seasonal deal"))));
            Material torchSeeds = this.matchMaterial("TORCHFLOWER_SEEDS");
            if (torchSeeds != null) {
                list.add(new Offer(6, 2, 18, 30, Material.EMERALD, 0, 0, (random) -> this.tagItem(new ItemStack(torchSeeds, 1), ChatColor.YELLOW + "Rare Seeds: Torchflower", List.of(ChatColor.GRAY + "Hard to find in the wild"))));
            }

            Material pitcherPod = this.matchMaterial("PITCHER_POD");
            if (pitcherPod != null) {
                list.add(new Offer(6, 2, 18, 30, Material.EMERALD, 0, 0, (random) -> this.tagItem(new ItemStack(pitcherPod, 1), ChatColor.YELLOW + "Rare Seeds: Pitcher", List.of(ChatColor.GRAY + "Hard to find in the wild"))));
            }

            list.add(new Offer(8, 6, 4, 10, (Material) null, 0, 0, (random) -> new ItemStack(Material.MELON_SEEDS, 24)));
            list.add(new Offer(8, 6, 4, 10, (Material) null, 0, 0, (random) -> new ItemStack(Material.PUMPKIN_SEEDS, 24)));
            list.add(new Offer(6, 2, 10, 18, (Material) null, 0, 0, (random) -> new ItemStack(Material.BEE_NEST, 1)));
        }

        if (theme == 1) {
            list.add(new Offer(10, 8, 5, 10, (Material) null, 0, 0, (random) -> new ItemStack(Material.IRON_INGOT, 16)));
            list.add(new Offer(8, 6, 7, 12, (Material) null, 0, 0, (random) -> new ItemStack(Material.GOLD_INGOT, 10)));
            list.add(new Offer(10, 2, 12, 20, (Material) null, 0, 0, (random) -> new ItemStack(Material.AMETHYST_SHARD, 24)));
            list.add(new Offer(10, 2, 12, 20, Material.DIAMOND, 1, 1, (random) -> new ItemStack(Material.NETHERITE_SCRAP, 1)));
            list.add(new Offer(8, 2, 9, 15, (Material) null, 0, 0, (random) -> new ItemStack(Material.QUARTZ, 32)));
        }

        if (theme == 2) {
            list.add(new Offer(10, 2, 18, 30, (Material) null, 0, 0, (random) -> this.makeEnchantedBook(random, season, ChatColor.AQUA + "Enchanter's Pick", this.enchRanges("efficiency", new int[]{2, 4}, "unbreaking", new int[]{2, 3}, "fortune", new int[]{1, 3}, "silk_touch", new int[]{1, 1}), 1)));
            list.add(new Offer(10, 2, 18, 30, (Material) null, 0, 0, (random) -> this.makeEnchantedBook(random, season, ChatColor.LIGHT_PURPLE + "Combat Manual", this.enchRanges("sharpness", new int[]{2, 4}, "unbreaking", new int[]{2, 3}, "fire_aspect", new int[]{1, 2}, "looting", new int[]{1, 3}), 1)));
            list.add(new Offer(8, 2, 16, 26, (Material) null, 0, 0, (random) -> this.makeEnchantedTool(random, season, Material.IRON_PICKAXE, ChatColor.YELLOW + "Lucky Pick", this.enchRanges("efficiency", new int[]{2, 3}, "unbreaking", new int[]{1, 2}), 2)));
        }

        if (theme == 3) {
            list.add(new Offer(10, 3, 14, 22, (Material) null, 0, 0, (random) -> new ItemStack(Material.NAME_TAG, 1)));
            list.add(new Offer(9, 2, 16, 24, (Material) null, 0, 0, (random) -> new ItemStack(Material.SADDLE, 1)));
            list.add(new Offer(8, 4, 10, 18, (Material) null, 0, 0, (random) -> new ItemStack(Material.LEAD, 4)));
            list.add(new Offer(8, 6, 8, 14, (Material) null, 0, 0, (random) -> new ItemStack(Material.FIREWORK_ROCKET, 24)));
            list.add(new Offer(7, 3, 12, 20, (Material) null, 0, 0, (random) -> new ItemStack(Material.GLOW_INK_SAC, 8)));
            list.add(new Offer(6, 2, 18, 30, (Material) null, 0, 0, (random) -> this.pickMusicDisc(random)));
        }

        return list;
    }

    /**
     * 构建通用（每日市场）交易池。
     */
    private List<Offer> buildCommonPool(Season season) {
        List<Offer> list = new ArrayList();
        list.add(new Offer(10, 6, 6, 12, (Material) null, 0, 0, (random) -> new ItemStack(Material.BREAD, 16)));
        list.add(new Offer(10, 6, 6, 12, (Material) null, 0, 0, (random) -> new ItemStack(Material.COOKED_BEEF, 12)));
        list.add(new Offer(9, 6, 8, 14, (Material) null, 0, 0, (random) -> new ItemStack(Material.EXPERIENCE_BOTTLE, 8)));
        list.add(new Offer(8, 4, 10, 18, (Material) null, 0, 0, (random) -> new ItemStack(Material.LAPIS_LAZULI, 24)));
        list.add(new Offer(9, 5, 5, 9, (Material) null, 0, 0, (random) -> new ItemStack(Material.IRON_INGOT, 8)));
        list.add(new Offer(8, 4, 6, 11, (Material) null, 0, 0, (random) -> new ItemStack(Material.GOLD_INGOT, 6)));
        list.add(new Offer(8, 2, 10, 18, (Material) null, 0, 0, (random) -> this.makeEnchantedBook(random, season, ChatColor.AQUA + "Mystery Enchant", this.enchRanges("unbreaking", new int[]{1, 3}, "efficiency", new int[]{1, 4}, "protection", new int[]{1, 4}, "sharpness", new int[]{1, 4}, "power", new int[]{1, 4}), 1)));
        return list;
    }

    /**
     * 制作一本带有随机 1~N 个附魔的附魔书。
     */
    private ItemStack makeEnchantedBook(Random random, Season season, String title, Map<Enchantment, int[]> pool, int enchCount) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta base = book.getItemMeta();
        if (base instanceof EnchantmentStorageMeta meta) {
            List<Enchantment> keys = new ArrayList(pool.keySet());
            Collections.shuffle(keys, random);
            int picks = Math.max(1, Math.min(enchCount, keys.size()));

            for (int i = 0; i < picks; ++i) {
                Enchantment enchantment = (Enchantment) keys.get(i);
                int[] range = (int[]) pool.get(enchantment);
                int level = range == null ? 1 : this.randBetween(random, range[0], range[1]);
                meta.addStoredEnchant(enchantment, level, true);
            }

            meta.setDisplayName(title);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            List<String> lore = new ArrayList();
            String grayPrefix = String.valueOf(ChatColor.GRAY);
            lore.add(grayPrefix + "Season: " + season.name());
            lore.add(ChatColor.DARK_GRAY + "Limited festival stock");
            meta.setLore(lore);
            book.setItemMeta(meta);
            return book;
        } else {
            return book;
        }
    }

    /**
     * 制作一件带有随机 1~N 个附魔的工具/武器。
     */
    private ItemStack makeEnchantedTool(Random random, Season season, Material baseMat, String title, Map<Enchantment, int[]> pool, int enchCount) {
        ItemStack item = new ItemStack(baseMat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            List<Enchantment> keys = new ArrayList(pool.keySet());
            Collections.shuffle(keys, random);
            int picks = Math.max(1, Math.min(enchCount, keys.size()));

            for (int i = 0; i < picks; ++i) {
                Enchantment enchantment = (Enchantment) keys.get(i);
                int[] range = (int[]) pool.get(enchantment);
                int level = range == null ? 1 : this.randBetween(random, range[0], range[1]);
                item.addUnsafeEnchantment(enchantment, level);
            }

            meta.setDisplayName(title);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            List<String> lore = new ArrayList();
            String grayPrefix = String.valueOf(ChatColor.GRAY);
            lore.add(grayPrefix + "Season: " + season.name());
            lore.add(ChatColor.DARK_GRAY + "Festival crafted");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
    }

    /**
     * 设置物品显示名与 lore（可选追加额外 lore 行）。
     */
    private ItemStack tagItem(ItemStack item, String name, List<String> loreExtra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList();
            if (loreExtra != null) {
                lore.addAll(loreExtra);
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
    }

    /**
     * 从固定唱片列表中随机挑选一张唱片。
     */
    private ItemStack pickMusicDisc(Random random) {
        Material[] pool = new Material[]{Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD, Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_11, Material.MUSIC_DISC_WAIT};
        return new ItemStack(pool[random.nextInt(pool.length)], 1);
    }

    /**
     * 随机挑选一个当前服务端存在的盔甲纹饰锻造模板，找不到则返回 null。
     */
    private Material pickTrimTemplate(Random random, Season season) {
        List<String> names = new ArrayList(Arrays.asList("SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE", "DUNE_ARMOR_TRIM_SMITHING_TEMPLATE", "COAST_ARMOR_TRIM_SMITHING_TEMPLATE", "WILD_ARMOR_TRIM_SMITHING_TEMPLATE", "WARD_ARMOR_TRIM_SMITHING_TEMPLATE", "VEX_ARMOR_TRIM_SMITHING_TEMPLATE", "TIDE_ARMOR_TRIM_SMITHING_TEMPLATE", "SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE", "RIB_ARMOR_TRIM_SMITHING_TEMPLATE", "EYE_ARMOR_TRIM_SMITHING_TEMPLATE", "SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE", "SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE", "WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE", "RAISER_ARMOR_TRIM_SMITHING_TEMPLATE", "SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE", "HOST_ARMOR_TRIM_SMITHING_TEMPLATE"));
        Collections.shuffle(names, random);

        for (String name : names) {
            Material material = this.matchMaterial(name);
            if (material != null) {
                return material;
            }
        }

        return null;
    }

    /**
     * 兼容性地按名称匹配 Material，失败返回 null。
     */
    private Material matchMaterial(String name) {
        try {
            return Material.matchMaterial(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 监听与商贩交互：立即刷新附近商贩，并显示节日集市动作栏提示。
     */
    @EventHandler(
            priority = EventPriority.NORMAL,
            ignoreCancelled = true
    )
    public void onInteractMerchant(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager || event.getRightClicked() instanceof WanderingTrader) {
            this.tryInstantFestivalApply(event.getPlayer());
            PersistentDataContainer container = event.getRightClicked().getPersistentDataContainer();
            Byte role = (Byte) container.get(this.FESTIVAL_ROLE_KEY, PersistentDataType.BYTE);
            String season = (String) container.get(this.FESTIVAL_SEASON_KEY, PersistentDataType.STRING);
            if (role != null && role != 0 && season != null) {
                String seasonName = this.plugin.lang.tr(event.getPlayer(), "season." + season);
                String msg = this.plugin.lang.trOr(event.getPlayer(), "event.festival.actionbar", "&6Festival Market&7 • &e{season}&8 • &7Stock changes daily").replace("{season}", seasonName);
                this.sendActionBarSafe(event.getPlayer(), msg);
            }
        }
    }

    /**
     * 通过反射安全地发送动作栏消息（兼容不同服务端 API）。
     */
    private void sendActionBarSafe(Player player, String msg) {
        try {
            Method method = player.getClass().getMethod("sendActionBar", String.class);
            method.invoke(player, msg);
        } catch (Throwable ignored) {
        }

    }

    /**
     * 单条节日交易模板。
     *
     * <p>保存权重、最大使用次数、绿宝石价格区间、可选附加材料及其数量区间，
     * 以及用于惰性生成结果物品的 {@link ResultBuilder}。</p>
     */
    private static final class Offer {
        /**
         * 抽取权重。
         */
        final int weight;
        /**
         * 最大使用次数。
         */
        final int maxUses;
        /**
         * 绿宝石价格下限。
         */
        final int emeraldMin;
        /**
         * 绿宝石价格上限。
         */
        final int emeraldMax;
        /**
         * 附加材料的物品类型（null 表示无）。
         */
        final Material extraMat;
        /**
         * 附加材料数量下限。
         */
        final int extraMin;
        /**
         * 附加材料数量上限。
         */
        final int extraMax;
        /**
         * 结果物品构建器。
         */
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

    /**
     * 结果物品构建器：给定随机源生成一件交易结果物品。
     */
    @FunctionalInterface
    private interface ResultBuilder {
        ItemStack build(Random random);
    }
}
