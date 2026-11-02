package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 热浪事件（Heat Wave）。
 *
 * <p>仅在夏季触发，且每个夏季有次数上限。事件期间，身处主世界（极寒世界除外）
 * 且暴露在阳光下的玩家：
 * <ul>
 *     <li>会持续获得“虚弱”效果；</li>
 *     <li>若护甲件数不足，还会周期性受到少量灼烧伤害并伴随火焰粒子。</li>
 * </ul>
 * 处于阴影下（头顶有方块遮挡）的玩家不受影响。</p>
 */
public final class HeatWaveEvent implements SeasonalEvent {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /** 是否启用全局粒子特效。 */
    private boolean globalParticlesEnabled;
    /** 每个夏季最多触发次数。 */
    private int maxPerSummer;
    /** 事件最短持续天数。 */
    private int minDur;
    /** 事件最长持续天数。 */
    private int maxDur;
    /** 每天触发事件的基础概率。 */
    private double baseChance;
    /** 是否对暴露玩家造成持续伤害。 */
    private boolean damageExposed;
    /** 每次 tick 的伤害量。 */
    private double tickDamage;
    /** 是否施加虚弱效果。 */
    private boolean applyWeakness;
    /** 免除伤害所需的最少护甲件数。 */
    private int minArmorProtection;
    /** 记录“年_季节”维度下已触发次数。 */
    private final Map<String, Integer> timesPerSummerKey = new HashMap();

    public HeatWaveEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /** 从 events.yml 重新读取本事件的配置项。 */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.heat_wave.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.maxPerSummer = config.getInt("events.heat_wave.max_per_summer", 3);
        this.minDur = config.getInt("events.heat_wave.min_duration_days", 1);
        this.maxDur = config.getInt("events.heat_wave.max_duration_days", 2);
        this.baseChance = config.getDouble("events.heat_wave.base_chance_per_day", 0.12);
        this.damageExposed = config.getBoolean("events.heat_wave.damage_exposed", true);
        this.tickDamage = config.getDouble("events.heat_wave.tick_damage", (double)0.5F);
        this.applyWeakness = config.getBoolean("events.heat_wave.apply_weakness", true);
        this.minArmorProtection = config.getInt("events.heat_wave.min_armor_protection", 2);
    }

    public String getId() {
        return "heat_wave";
    }

    public String getDisplayName() {
        return "Heat Wave";
    }

    public boolean isSeasonAllowed(Season season) {
        return season == Season.SUMMER;
    }

    public int getMinDurationDays() {
        return this.minDur;
    }

    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /** 判定是否可开始：已启用、当前为夏季、本夏次数未超上限且通过基础概率。 */
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else if (state.season != Season.SUMMER) {
            return false;
        } else {
            int year = state.year;
            String key = year + "_" + state.season.name();
            int used = (Integer)this.timesPerSummerKey.getOrDefault(key, 0);
            if (used >= this.maxPerSummer) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /** 事件开始：累计本夏触发次数，并向非极寒世界播放音效与发送标题。 */
    public void onStart(CalendarState state, EventContext context) {
        int year = state.year;
        String key = year + "_" + state.season.name();
        this.timesPerSummerKey.put(key, (Integer)this.timesPerSummerKey.getOrDefault(key, 0) + 1);

        for(World world : context.overworlds()) {
            if (!world.getName().equalsIgnoreCase("aeternum_frost")) {
                world.playSound(world.getSpawnLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.7F, 1.0F);
            }
        }

        for(Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().getName().equalsIgnoreCase("aeternum_frost")) {
                String title = this.plugin.lang.tr(player, "event.heat_wave.title");
                String sub = this.plugin.lang.tr(player, "event.heat_wave.subtitle");
                player.sendTitle(title, sub, 20, 80, 40);
            }
        }

    }

    /** 事件结束：向非极寒世界玩家发送结束提示。 */
    public void onEnd(CalendarState state, EventContext context) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().getName().equalsIgnoreCase("aeternum_frost")) {
                player.sendMessage(this.plugin.lang.tr(player, "event.heat_wave.end"));
            }
        }

    }

    public void onDayTick(CalendarState state, EventContext context) {
    }

    /** 每个 tick 对暴露在阳光下的玩家施加虚弱与灼烧伤害。 */
    public void onTick(CalendarState state, EventContext context) {
        if (this.damageExposed || this.applyWeakness) {
            for(Player player : Bukkit.getOnlinePlayers()) {
                World playerWorld = player.getWorld();
                if (playerWorld.getEnvironment() == Environment.NORMAL && !playerWorld.getName().equalsIgnoreCase("aeternum_frost") && !player.isDead() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR && !this.isInShade(player)) {
                    int armorScore = this.countArmorPieces(player);
                    if (this.applyWeakness) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, false, true));
                    }

                    if (this.damageExposed && armorScore < this.minArmorProtection) {
                        player.damage(this.tickDamage);
                        if (this.globalParticlesEnabled) {
                            player.spawnParticle(Particle.FLAME, player.getLocation().add((double)0.0F, 1.2, (double)0.0F), 4, 0.2, 0.3, 0.2, 0.01);
                        }
                    }
                }
            }

        }
    }

    /** 判断玩家是否处于阴影下（头顶最高方块高于其上方一格）。 */
    private boolean isInShade(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int y = loc.getBlockY() + 1;
        int z = loc.getBlockZ();
        int highestY = world.getHighestBlockYAt(x, z);
        return highestY > y;
    }

    /** 统计玩家当前穿戴的护甲件数。 */
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
}
