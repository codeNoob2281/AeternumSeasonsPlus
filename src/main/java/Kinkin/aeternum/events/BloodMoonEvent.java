package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * 血月事件（Blood Moon）。
 *
 * <p>在满足配置条件（最低天数、每季次数上限、夜晚概率）时触发。事件激活期间：
 * <ul>
 *     <li>主世界夜晚生成的敌对生物会被“血月化”——记录并提升最大生命与攻击力，
 *         并赋予自定义名称与发光效果；</li>
 *     <li>禁止玩家在主世界夜晚睡觉。</li>
 * </ul>
 * 事件结束时会把受影响的生物恢复到事件开始前的属性与名称。原始属性通过
 * {@link PersistentDataContainer} 暂存，以便安全还原。事件结束时也会触发清理。</p>
 */
public final class BloodMoonEvent implements SeasonalEvent, Listener {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 每个夜晚触发的概率。
     */
    private double chancePerNight;
    /**
     * 允许触发的最早游戏日。
     */
    private int minDay;
    /**
     * 每个季节（年+季节）最多触发次数。
     */
    private int maxTimesPerSeason;
    /**
     * 是否将世界时间强制推进到夜晚。
     */
    private boolean warpToNight;
    /**
     * 是否冻结夜晚（阻止时间流逝）。
     */
    private boolean freezeNight;
    /**
     * 事件当前是否处于激活状态。
     */
    private boolean active = false;
    /**
     * 记录“年_季节”维度下已触发次数。
     */
    private final Map<String, Integer> timesPerSeasonKey = new HashMap();
    private static final String TAG = "aet_blood_moon";
    /**
     * 暂存生物原有名称。
     */
    private final NamespacedKey K_NAME;
    /**
     * 暂存生物原有名称是否可见。
     */
    private final NamespacedKey K_NAME_VISIBLE;
    /**
     * 暂存生物原有最大生命值。
     */
    private final NamespacedKey K_MAX_HEALTH;
    /**
     * 暂存生物原有攻击力。
     */
    private final NamespacedKey K_ATTACK_DMG;

    public BloodMoonEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
        this.K_NAME = new NamespacedKey(plugin, "bloodmoon_prev_name");
        this.K_NAME_VISIBLE = new NamespacedKey(plugin, "bloodmoon_prev_name_visible");
        this.K_MAX_HEALTH = new NamespacedKey(plugin, "bloodmoon_prev_max_health");
        this.K_ATTACK_DMG = new NamespacedKey(plugin, "bloodmoon_prev_attack_dmg");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.blood_moon.enabled", true);
        this.chancePerNight = config.getDouble("events.blood_moon.chance_per_night", 0.08);
        this.minDay = config.getInt("events.blood_moon.min_day", 5);
        this.maxTimesPerSeason = config.getInt("events.blood_moon.max_times_per_season", 2);
        this.warpToNight = config.getBoolean("events.blood_moon.world_time_night", false);
        this.freezeNight = config.getBoolean("events.blood_moon.freeze_night", false);
    }

    @Override
    public String getId() {
        return "blood_moon";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.blood_moon.display_name", "&4Blood Moon");
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
     * 判定是否满足触发条件：已启用、达到最低天数、且本季未超次数上限，并通过夜晚概率。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else if (state.day < this.minDay) {
            return false;
        } else {
            int year = state.year;
            String key = year + "_" + state.season.name();
            int used = (Integer) this.timesPerSeasonKey.getOrDefault(key, 0);
            if (used >= this.maxTimesPerSeason) {
                return false;
            } else {
                return Math.random() < this.chancePerNight;
            }
        }
    }

    /**
     * 事件开始：累计本季触发次数、置为激活，并对主世界玩家发送标题与音效。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        int year = state.year;
        String key = year + "_" + state.season.name();
        this.timesPerSeasonKey.put(key, (Integer) this.timesPerSeasonKey.getOrDefault(key, 0) + 1);
        this.active = true;
        World world = Bukkit.getWorld("world");
        if (world != null) {
            world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
            world.playSound(world.getSpawnLocation(), Sound.AMBIENT_CAVE, 0.8F, 0.5F);

            for (Player player : world.getPlayers()) {
                String title = this.plugin.lang.tr(player, "event.blood_moon.title");
                String sub = this.plugin.lang.tr(player, "event.blood_moon.subtitle");
                player.sendTitle(title, sub, 20, 80, 40);
            }

        }
    }

    /**
     * 事件结束：取消激活、清理受影响生物并通知玩家。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.active = false;
        World world = Bukkit.getWorld("world");
        if (world != null) {
            this.cleanupWorld(world);

            for (Player player : world.getPlayers()) {
                player.sendMessage(this.plugin.lang.tr(player, "event.blood_moon.end"));
            }

        }
    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 判断世界当前是否为夜晚（13000~24000 tick）。
     */
    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000L && time < 24000L;
    }

    /**
     * 血月期间禁止玩家在主世界夜晚睡觉。
     */
    @EventHandler
    public void onBed(PlayerBedEnterEvent event) {
        if (this.active) {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if ("world".equalsIgnoreCase(world.getName())) {
                if (world.getEnvironment() == Environment.NORMAL) {
                    if (this.isNight(world)) {
                        event.setCancelled(true);
                        player.sendMessage(this.plugin.lang.tr(player, "event.blood_moon.no_sleep"));
                    }
                }
            }
        }
    }

    /**
     * 血月期间主世界夜晚生成的敌对生物会被血月化（打标签、强化属性、改名称）。
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (this.active) {
            LivingEntity entity = event.getEntity();
            if (entity instanceof Monster) {
                Monster mob = (Monster) entity;
                if (!this.isCitizensNPC(mob)) {
                    World world = mob.getWorld();
                    if ("world".equalsIgnoreCase(world.getName())) {
                        if (world.getEnvironment() == Environment.NORMAL) {
                            if (this.isNight(world)) {
                                mob.addScoreboardTag("aet_blood_moon");
                                this.saveAndBuffAttributes(mob);
                                if (mob.getCustomName() == null) {
                                    this.saveAndApplyName(mob);
                                }

                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 保存并覆盖生物的自定义名称，标记其名称可见。
     */
    private void saveAndApplyName(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(this.K_NAME, PersistentDataType.STRING, "");
        pdc.set(this.K_NAME_VISIBLE, PersistentDataType.BYTE, (byte) (entity.isCustomNameVisible() ? 1 : 0));
        String mobName = this.plugin.lang.tr((Player) null, "event.blood_moon.mob_name");
        entity.setCustomName("§4" + mobName);
        entity.setCustomNameVisible(true);
    }

    /**
     * 保存生物原有属性，并按倍率提升最大生命与攻击力。
     */
    private void saveAndBuffAttributes(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            if (!pdc.has(this.K_MAX_HEALTH, PersistentDataType.DOUBLE)) {
                pdc.set(this.K_MAX_HEALTH, PersistentDataType.DOUBLE, maxHealth.getBaseValue());
            }

            double original = maxHealth.getBaseValue();
            double boosted = original * (double) 1.5F;
            double cap = (double) 1024.0F;
            double finalValue = Math.min(boosted, cap);
            maxHealth.setBaseValue(finalValue);
            entity.setHealth(Math.min(entity.getHealth(), finalValue));
            entity.setHealth(finalValue);
        }

        AttributeInstance dmg = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) {
            if (!pdc.has(this.K_ATTACK_DMG, PersistentDataType.DOUBLE)) {
                pdc.set(this.K_ATTACK_DMG, PersistentDataType.DOUBLE, dmg.getBaseValue());
            }

            dmg.setBaseValue(dmg.getBaseValue() * (double) 1.5F);
        }

    }

    /**
     * 清除世界内所有血月化生物的影响，恢复其名称与属性。
     */
    private void cleanupWorld(World world) {
        for (Monster mob : world.getEntitiesByClass(Monster.class)) {
            if (mob.getScoreboardTags().contains("aet_blood_moon")) {
                PersistentDataContainer pdc = mob.getPersistentDataContainer();
                if (pdc.has(this.K_NAME, PersistentDataType.STRING)) {
                    String prev = (String) pdc.get(this.K_NAME, PersistentDataType.STRING);
                    byte vis = (Byte) pdc.getOrDefault(this.K_NAME_VISIBLE, PersistentDataType.BYTE, (byte) 0);
                    if (prev != null && !prev.isEmpty()) {
                        mob.setCustomName(prev);
                    } else {
                        mob.setCustomName((String) null);
                    }

                    mob.setCustomNameVisible(vis == 1);
                    pdc.remove(this.K_NAME);
                    pdc.remove(this.K_NAME_VISIBLE);
                }

                if (pdc.has(this.K_MAX_HEALTH, PersistentDataType.DOUBLE)) {
                    Double prevMax = (Double) pdc.get(this.K_MAX_HEALTH, PersistentDataType.DOUBLE);
                    AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
                    if (prevMax != null && maxHealth != null) {
                        maxHealth.setBaseValue(prevMax);
                        mob.setHealth(Math.min(mob.getHealth(), prevMax));
                    }

                    pdc.remove(this.K_MAX_HEALTH);
                }

                if (pdc.has(this.K_ATTACK_DMG, PersistentDataType.DOUBLE)) {
                    Double prevDmg = (Double) pdc.get(this.K_ATTACK_DMG, PersistentDataType.DOUBLE);
                    AttributeInstance dmg = mob.getAttribute(Attribute.ATTACK_DAMAGE);
                    if (prevDmg != null && dmg != null) {
                        dmg.setBaseValue(prevDmg);
                    }

                    pdc.remove(this.K_ATTACK_DMG);
                }

                mob.removeScoreboardTag("aet_blood_moon");
            }
        }

    }

    /**
     * 判断实体是否为 Citizens 插件生成的 NPC（通过元数据标记识别）。
     */
    private boolean isCitizensNPC(Entity entity) {
        return entity != null && entity.hasMetadata("NPC");
    }
}
