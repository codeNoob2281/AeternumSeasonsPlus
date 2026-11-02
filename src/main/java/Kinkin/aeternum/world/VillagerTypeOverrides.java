package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import Kinkin.aeternum.lang.LanguageManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.entity.Villager.Type;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 村民类型与职业轮换覆盖。
 *
 * <p>包含两套彼此独立的能力：
 * <ul>
 *     <li>外观覆盖：新生/自然生成的村民按概率随机赋予配置允许的生物群系类型（外观）；</li>
 *     <li>职业轮换：每隔若干日历天，将玩家附近已交易与未交易的村民重新分配职业，
 *     并按“懒人比例”把一部分未交易村民设为傻子（NITWIT），同时维护其自定义名称上的“懒人”标签。</li>
 * </ul>
 * 依赖 {@link LanguageManager} 获取多语言的懒人标签，并将最后轮换日期持久化到
 * {@code data/villager_override_state.yml}。</p>
 */
public final class VillagerTypeOverrides implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager langManager;
    private boolean enabled;
    private boolean appearanceEnabled;
    private boolean professionRotationEnabled;
    /** 外观覆盖生效的概率。 */
    private double overrideChance;
    /** 是否在村民名称上显示“懒人”标签。 */
    private boolean showLazyTag = true;
    /** 仅在这些世界名中生效（为空表示全部世界）。 */
    private final Set<String> worlds = new HashSet();
    /** 允许随机赋予的村民类型。 */
    private final List<Villager.Type> allowedTypes = new ArrayList();
    /** 所有语言的懒人标签集合，用于从名称中移除。 */
    private Set<String> allLazyTags = Collections.emptySet();
    private int lastUpdateDay = -1;
    private static final int VILLAGE_RANGE_CHUNKS = 10;
    /** 职业轮换的间隔天数。 */
    private int updatePeriodDays = 5;
    /** 每个区域内最多设为懒人的村民数量。 */
    private int maxLazyPerArea = 2;
    /** 懒人占候选村民的比例。 */
    private double lazyRatio = (double)0.25F;
    private int currentCalendarDay = -1;

    public VillagerTypeOverrides(AeternumSeasonsPlugin plugin, LanguageManager langManager) {
        super();
        this.plugin = plugin;
        this.langManager = langManager;
        this.loadState();
        this.reloadFromConfig();
    }

    /** 从数据文件读取上次轮换日期。 */
    private void loadState() {
        File stateFile = new File(this.plugin.getDataFolder(), "data/villager_override_state.yml");
        if (!stateFile.exists()) {
            this.lastUpdateDay = -1;
        } else {
            YamlConfiguration stateYaml = YamlConfiguration.loadConfiguration(stateFile);
            this.lastUpdateDay = stateYaml.getInt("last_update_day", -1);
        }
    }

    /** 将上次轮换日期持久化到数据文件。 */
    private void saveState() {
        try {
            File stateFile = new File(this.plugin.getDataFolder(), "data/villager_override_state.yml");
            if (!stateFile.getParentFile().exists()) {
                stateFile.getParentFile().mkdirs();
            }

            YamlConfiguration stateYaml = new YamlConfiguration();
            stateYaml.set("last_update_day", this.lastUpdateDay);
            stateYaml.save(stateFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("No se pudo guardar villager_override_state.yml: " + ex.getMessage());
        }

    }

    /** 从 villager_type_overrides 配置节重新读取全部参数。 */
    public void reloadFromConfig() {
        ConfigurationSection sec = this.plugin.cfg.climate.getConfigurationSection("villager_type_overrides");
        if (sec == null) {
            this.enabled = false;
            this.appearanceEnabled = false;
            this.professionRotationEnabled = false;
            this.worlds.clear();
            this.allowedTypes.clear();
        } else {
            this.enabled = sec.getBoolean("enabled", false);
            this.appearanceEnabled = this.enabled && sec.getBoolean("appearance.enabled", true);
            this.professionRotationEnabled = this.enabled && sec.getBoolean("profession_rotation.enabled", false);
            this.overrideChance = Math.max((double)0.0F, Math.min((double)1.0F, sec.getDouble("appearance.override_chance", sec.getDouble("override_chance", (double)1.0F))));
            this.showLazyTag = sec.getBoolean("profession_rotation.show_lazy_tag", sec.getBoolean("show_lazy_tag", true));
            this.updatePeriodDays = Math.max(1, sec.getInt("profession_rotation.update_period_days", 5));
            this.maxLazyPerArea = Math.max(0, sec.getInt("profession_rotation.max_lazy_per_area", 2));
            this.lazyRatio = Math.max((double)0.0F, Math.min(0.9, sec.getDouble("profession_rotation.lazy_ratio", (double)0.25F)));
            this.worlds.clear();

            for(String worldName : sec.getStringList("worlds")) {
                if (worldName != null && !worldName.isEmpty()) {
                    this.worlds.add(worldName);
                }
            }

            this.allowedTypes.clear();

            for(String typeName : sec.getStringList("allowed_types")) {
                try {
                    Villager.Type villagerType = Type.valueOf(typeName.toUpperCase(Locale.ROOT));
                    this.allowedTypes.add(villagerType);
                } catch (IllegalArgumentException ignored) {
                    this.plugin.getLogger().warning("[VillagerTypes] Invalid villager type '" + typeName + "' in allowed_types");
                }
            }

            if (this.allowedTypes.isEmpty()) {
                this.allowedTypes.addAll(Arrays.asList(Type.PLAINS, Type.DESERT, Type.SAVANNA, Type.TAIGA, Type.SNOW, Type.SWAMP, Type.JUNGLE));
            }

            this.allLazyTags = this.langManager.getAllTranslations("villager.lazy_tag");
            Logger logger = this.plugin.getLogger();
            boolean enabledValue = this.enabled;
            logger.info("[VillagerTypes] Enabled=" + enabledValue + ", appearanceEnabled=" + this.appearanceEnabled + ", professionRotationEnabled=" + this.professionRotationEnabled + ", showLazyTag=" + this.showLazyTag + ", worlds=" + String.valueOf(this.worlds) + ", allowedTypes=" + String.valueOf(this.allowedTypes));
        }
    }

    /**
     * 监听村民生成：对符合条件的生成原因按概率覆盖外观，并在开启职业轮换时立即刷新其显示名称。
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (this.enabled) {
            if (e.getEntityType() == EntityType.VILLAGER) {
                CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
                switch (reason) {
                    case BREEDING:
                    case CURED:
                    case NATURAL:
                    case JOCKEY:
                    case COMMAND:
                    case SPAWNER_EGG:
                        World world = e.getLocation().getWorld();
                        if (world != null && (this.worlds.isEmpty() || this.worlds.contains(world.getName()))) {
                            Villager villager = (Villager)e.getEntity();
                            if (this.appearanceEnabled && villager.getVillagerLevel() <= 1 && ThreadLocalRandom.current().nextDouble() <= this.overrideChance) {
                                Villager.Type newType = (Villager.Type)this.allowedTypes.get(ThreadLocalRandom.current().nextInt(this.allowedTypes.size()));
                                villager.setVillagerType(newType);
                            }

                            if (this.professionRotationEnabled) {
                                this.updateVillagerDisplay(villager, (Player)null);
                            }

                            return;
                        } else {
                            return;
                        }
                    default:
                }
            }
        }
    }

    /** 监听季节更新事件：记录当前日历天，供轮换周期判断使用。 */
    @EventHandler
    public void onSeasonUpdate(SeasonUpdateEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            CalendarState state = e.getState();
            this.currentCalendarDay = state.day;
        }
    }

    /** 监听玩家跨区块移动：可能触发到期的村庄轮换。 */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            if (e.getTo() != null) {
                Chunk from = e.getFrom().getChunk();
                Chunk to = e.getTo().getChunk();
                if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                    this.maybeUpdateVillagersIfPeriodPassed();
                }
            }
        }
    }

    /** 监听玩家加入：延迟一段时间后检查是否需要更新村庄。 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
                if (e.getPlayer().isOnline()) {
                    this.maybeUpdateVillagersIfPeriodPassed();
                }

            }, 40L);
        }
    }

    /** 若距上次轮换已超过配置天数，则调度一次村庄更新。 */
    private void maybeUpdateVillagersIfPeriodPassed() {
        if (this.enabled && this.professionRotationEnabled) {
            if (this.currentCalendarDay > 0) {
                boolean anyPlayerInConfiguredWorld = false;

                for(Player player : this.plugin.getServer().getOnlinePlayers()) {
                    if (this.worlds.contains(player.getWorld().getName())) {
                        anyPlayerInConfiguredWorld = true;
                        break;
                    }
                }

                if (anyPlayerInConfiguredWorld) {
                    boolean shouldUpdate;
                    if (this.lastUpdateDay == -1) {
                        shouldUpdate = true;
                    } else if (this.currentCalendarDay >= this.lastUpdateDay) {
                        shouldUpdate = this.currentCalendarDay - this.lastUpdateDay >= this.updatePeriodDays;
                    } else {
                        shouldUpdate = true;
                    }

                    if (shouldUpdate) {
                        int previousDay = this.lastUpdateDay;
                        this.plugin.getLogger().info("[VillagerTypes] Have approved at least " + this.updatePeriodDays + " days since the last rotation (" + previousDay + " -> " + this.currentCalendarDay + "). Updating villagers close to players.");
                        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                            this.performVillageUpdate();
                            this.lastUpdateDay = this.currentCalendarDay;
                            this.saveState();
                        });
                    }
                }
            }
        }
    }

    /** 执行一次村庄更新：对每个玩家附近村庄范围的村民重新分配职业与外观。 */
    private void performVillageUpdate() {
        if (!this.allowedTypes.isEmpty()) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            for(Player player : this.plugin.getServer().getOnlinePlayers()) {
                World world = player.getWorld();
                if (this.worlds.contains(world.getName())) {
                    Chunk playerChunk = player.getLocation().getChunk();
                    int chunkRadius = 10;
                    List<Villager> candidates = new ArrayList();

                    for(int x = playerChunk.getX() - chunkRadius; x <= playerChunk.getX() + chunkRadius; ++x) {
                        for(int z = playerChunk.getZ() - chunkRadius; z <= playerChunk.getZ() + chunkRadius; ++z) {
                            if (world.isChunkLoaded(x, z)) {
                                for(Entity entity : world.getChunkAt(x, z).getEntities()) {
                                    if (entity.getType() == EntityType.VILLAGER) {
                                        Villager villager = (Villager)entity;
                                        boolean hasTraded = villager.getVillagerLevel() > 1 || villager.getVillagerExperience() > 0;
                                        if (hasTraded) {
                                            this.updateVillagerDisplay(villager, player);
                                        } else {
                                            candidates.add(villager);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!candidates.isEmpty()) {
                        Collections.shuffle(candidates, rnd);
                        int total = candidates.size();
                        int lazyToAssign = 0;
                        if (total >= 3) {
                            int lazyByRatio = (int)Math.floor((double)total * this.lazyRatio);
                            lazyToAssign = Math.max(1, lazyByRatio);
                            lazyToAssign = Math.min(this.maxLazyPerArea, lazyToAssign);
                            if (lazyToAssign >= total) {
                                lazyToAssign = total - 1;
                            }
                        }

                        for(int i = 0; i < total; ++i) {
                            Villager candidate = (Villager)candidates.get(i);
                            if (i < lazyToAssign) {
                                candidate.setProfession(Profession.NITWIT);
                            } else {
                                candidate.setProfession(Profession.NONE);
                            }

                            if (this.appearanceEnabled && rnd.nextDouble() <= this.overrideChance) {
                                Villager.Type newType = (Villager.Type)this.allowedTypes.get(rnd.nextInt(this.allowedTypes.size()));
                                candidate.setVillagerType(newType);
                            }

                            this.updateVillagerDisplay(candidate, player);
                        }
                    }
                }
            }

        }
    }

    /** 根据职业与配置刷新村民的自定义名称（懒人标签的增删）。 */
    private void updateVillagerDisplay(Villager villager, Player p) {
        String currentName = villager.getCustomName();
        if (villager.getProfession() == Profession.NITWIT) {
            String safeCurrentName = currentName != null ? currentName : "";
            String nameWithoutTag = this.removeAllLazyTags(safeCurrentName).trim();
            if (!this.showLazyTag) {
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName((String)null);
                    villager.setCustomNameVisible(false);
                } else if (!safeCurrentName.equals(nameWithoutTag)) {
                    villager.setCustomName(nameWithoutTag);
                    villager.setCustomNameVisible(true);
                }

            } else {
                String lazyTag = this.langManager.tr(p, "villager.lazy_tag");
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName(lazyTag);
                } else {
                    villager.setCustomName(nameWithoutTag + " " + lazyTag);
                }

                villager.setCustomNameVisible(true);
            }
        } else {
            if (currentName != null) {
                String nameWithoutTag = this.removeAllLazyTags(currentName).trim();
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName((String)null);
                    villager.setCustomNameVisible(false);
                } else if (!currentName.equals(nameWithoutTag)) {
                    villager.setCustomName(nameWithoutTag);
                    villager.setCustomNameVisible(true);
                }
            }

        }
    }

    /** 移除名称中包含的所有语言懒人标签并去除首尾空白。 */
    private String removeAllLazyTags(String name) {
        if (name == null) {
            return "";
        } else {
            String cleanName = name;

            for(String tag : this.allLazyTags) {
                cleanName = cleanName.replace(tag, "").trim();
            }

            String currentLazyTag = this.langManager.trServer("villager.lazy_tag");
            cleanName = cleanName.replace(currentLazyTag, "").trim();
            return cleanName;
        }
    }

    /** 监听玩家语言切换：刷新其附近村民的名称显示。 */
    @EventHandler
    public void onPlayerLocaleChange(PlayerLocaleChangeEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            this.checkNearbyVillagers(e.getPlayer());
        }
    }

    /** 刷新指定玩家附近村庄范围内所有村民的名称显示。 */
    private void checkNearbyVillagers(Player p) {
        World world = p.getWorld();
        if (this.worlds.contains(world.getName())) {
            Chunk playerChunk = p.getLocation().getChunk();
            int chunkRadius = 10;

            for(int x = playerChunk.getX() - chunkRadius; x <= playerChunk.getX() + chunkRadius; ++x) {
                for(int z = playerChunk.getZ() - chunkRadius; z <= playerChunk.getZ() + chunkRadius; ++z) {
                    if (world.isChunkLoaded(x, z)) {
                        for(Entity entity : world.getChunkAt(x, z).getEntities()) {
                            if (entity.getType() == EntityType.VILLAGER) {
                                this.updateVillagerDisplay((Villager)entity, p);
                            }
                        }
                    }
                }
            }

        }
    }
}
