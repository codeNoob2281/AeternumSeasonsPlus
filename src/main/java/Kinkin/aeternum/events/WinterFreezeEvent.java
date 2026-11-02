package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 寒冬冰封事件（Winter Freeze）。
 *
 * <p>仅在冬季发生，每个冬季最多触发配置的次数，且需过了季节前 1/3 才可能开始。
 * 事件激活期间，主世界中未穿戴足够护甲、且附近没有热源方块的玩家会持续受到
 * 减速、挖掘疲劳与少量冰冻伤害；护甲足够时仅受轻微减速。配置来自
 * {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class WinterFreezeEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /** 是否启用全局粒子效果。 */
    private boolean globalParticlesEnabled;
    /** 每个冬季最多触发次数。 */
    private int maxPerWinter;
    /** 事件最短持续天数。 */
    private int minDur;
    /** 事件最长持续天数。 */
    private int maxDur;
    /** 每天触发事件的基础概率。 */
    private double baseChance;
    /** 每 tick 造成的冰冻伤害。 */
    private double damagePerTick;
    /** 免于冰冻伤害所需的最少护甲件数。 */
    private int minArmorPieces;
    /** 搜索热源方块的半径（格）。 */
    private int heatRadius;
    /** 视为热源的方块类型集合。 */
    private Set<Material> heatBlocks;
    /** 记录每个“年份_季节”已触发次数。 */
    private final Map<String, Integer> timesPerWinterKey = new HashMap();

    public WinterFreezeEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /** 从 events.yml 重新读取本事件的配置项。 */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.winter_freeze.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.maxPerWinter = config.getInt("events.winter_freeze.max_per_winter", 1);
        this.minDur = config.getInt("events.winter_freeze.min_duration_days", 4);
        this.maxDur = config.getInt("events.winter_freeze.max_duration_days", 7);
        this.baseChance = config.getDouble("events.winter_freeze.base_chance_per_day", 0.15);
        this.damagePerTick = config.getDouble("events.winter_freeze.damage_per_tick", (double)0.5F);
        this.minArmorPieces = config.getInt("events.winter_freeze.min_armor_pieces", 2);
        this.heatRadius = config.getInt("events.winter_freeze.heat_radius_blocks", 5);
        this.heatBlocks = new HashSet();

        for(String materialName : config.getStringList("events.winter_freeze.heat_block_types")) {
            try {
                this.heatBlocks.add(Material.valueOf(materialName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (this.heatBlocks.isEmpty()) {
            this.heatBlocks.add(Material.CAMPFIRE);
            this.heatBlocks.add(Material.SOUL_CAMPFIRE);
            this.heatBlocks.add(Material.TORCH);
            this.heatBlocks.add(Material.LANTERN);
            this.heatBlocks.add(Material.FIRE);
            this.heatBlocks.add(Material.SOUL_TORCH);
        }

    }

    public String getId() {
        return "winter_freeze";
    }

    public String getDisplayName() {
        return "Winter Freeze";
    }

    public boolean isSeasonAllowed(Season season) {
        return season == Season.WINTER;
    }

    public int getMinDurationDays() {
        return this.minDur;
    }

    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /** 仅限冬季、未超过本冬配额、已过季节前 1/3，并通过概率判定时才允许开始。 */
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else if (state.season != Season.WINTER) {
            return false;
        } else {
            int year = state.year;
            String key = year + "_" + state.season.name();
            int used = (Integer)this.timesPerWinterKey.getOrDefault(key, 0);
            if (used >= this.maxPerWinter) {
                return false;
            } else {
                int daysPerSeason = context.seasons().getDaysPerSeason();
                int minStartDay = Math.max(2, daysPerSeason / 3);
                if (state.day < minStartDay) {
                    return false;
                } else {
                    return Math.random() < this.baseChance;
                }
            }
        }
    }

    /** 事件开始时累加本冬触发次数、播放寒潮音效并向所有玩家发送标题。 */
    public void onStart(CalendarState state, EventContext context) {
        int year = state.year;
        String key = year + "_" + state.season.name();
        this.timesPerWinterKey.put(key, (Integer)this.timesPerWinterKey.getOrDefault(key, 0) + 1);

        for(World world : context.overworlds()) {
            world.playSound(world.getSpawnLocation(), Sound.WEATHER_RAIN_ABOVE, 0.6F, 0.5F);
        }

        for(Player player : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(player, "event.winter_freeze.title");
            String subtitle = this.plugin.lang.tr(player, "event.winter_freeze.subtitle");
            player.sendTitle(title, subtitle, 20, 80, 40);
        }

    }

    /** 事件结束时通知所有在线玩家。 */
    public void onEnd(CalendarState state, EventContext context) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.winter_freeze.end"));
        }

    }

    public void onDayTick(CalendarState state, EventContext context) {
    }

    /** 每个 tick 检查主世界玩家，按护甲件数与是否靠近热源决定冰冻程度。 */
    public void onTick(CalendarState state, EventContext context) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NORMAL && !player.isDead() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR && !this.isNearHeat(player)) {
                int armorScore = this.countArmorPieces(player);
                if (armorScore >= this.minArmorPieces) {
                    this.maybeApplyLightChill(player);
                } else {
                    this.applyFreezeDamage(player);
                }
            }
        }

    }

    /** 对护甲不足的玩家施加冰冻伤害、减速与挖掘疲劳，并定时提示。 */
    private void applyFreezeDamage(Player player) {
        if (this.globalParticlesEnabled) {
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 10, (double)0.5F, 0.8, (double)0.5F, 0.01);
        }

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 3, true, false, true));
        if (player.getHealth() > this.damagePerTick + (double)0.5F) {
            player.damage(this.damagePerTick);
        }

        if (player.getTicksLived() % 60 == 0) {
            player.sendMessage(this.plugin.lang.tr(player, "event.winter_freeze.freezing"));
        }

    }

    /** 对护甲足够的玩家仅施加轻微减速。 */
    private void maybeApplyLightChill(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, false, true));
        if (player.getTicksLived() % 100 == 0) {
        }

    }

    /** 统计玩家当前穿戴的护甲件数（头盔/胸甲/护腿/靴子）。 */
    private int countArmorPieces(Player player) {
        int count = 0;
        if (player.getInventory().getHelmet() != null) {
            ++count;
        }

        if (player.getInventory().getChestplate() != null) {
            ++count;
        }

        if (player.getInventory().getLeggings() != null) {
            ++count;
        }

        if (player.getInventory().getBoots() != null) {
            ++count;
        }

        return count;
    }

    /** 判断玩家附近（含上下一定范围）是否存在热源方块。 */
    private boolean isNearHeat(Player player) {
        World world = player.getWorld();
        int playerX = player.getLocation().getBlockX();
        int playerY = player.getLocation().getBlockY();
        int playerZ = player.getLocation().getBlockZ();
        int radius = this.heatRadius;

        for(int x = -radius; x <= radius; ++x) {
            for(int y = -2; y <= 2; ++y) {
                for(int z = -radius; z <= radius; ++z) {
                    Material material = world.getBlockAt(playerX + x, playerY + y, playerZ + z).getType();
                    if (this.heatBlocks.contains(material)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
