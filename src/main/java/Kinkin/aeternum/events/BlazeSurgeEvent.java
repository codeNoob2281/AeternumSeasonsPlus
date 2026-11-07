package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 烈焰狂潮事件（Blaze Surge）。
 *
 * <p>当有玩家身处下界时，有一定概率触发本事件。事件激活期间：
 * <ul>
 *     <li>下界自然生成的烈焰人有概率被强化——附加速度效果、发光、并提升最大生命值；</li>
 *     <li>玩家击杀烈焰人时，有概率额外掉落若干烈焰棒并伴随火焰粒子。</li>
 * </ul>
 * 各概率与掉落数量均来自 events.yml 配置。</p>
 */
public final class BlazeSurgeEvent implements SeasonalEvent {
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
     * 击杀烈焰人额外掉落烈焰棒的概率。
     */
    private double extraRodChance;
    /**
     * 额外掉落烈焰棒的最小数量。
     */
    private int extraRodMin;
    /**
     * 额外掉落烈焰棒的最大数量。
     */
    private int extraRodMax;
    /**
     * 自然生成的烈焰人被强化的概率。
     */
    private double buffedBlazeChance;

    public BlazeSurgeEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.blaze_surge.enabled", true);
        this.minDur = config.getInt("events.blaze_surge.min_duration_days", 1);
        this.maxDur = config.getInt("events.blaze_surge.max_duration_days", 2);
        this.baseChance = config.getDouble("events.blaze_surge.base_chance_per_day", 0.08);
        this.extraRodChance = config.getDouble("events.blaze_surge.extra_rod_chance", 0.35);
        this.extraRodMin = config.getInt("events.blaze_surge.extra_rod_min", 1);
        this.extraRodMax = config.getInt("events.blaze_surge.extra_rod_max", 2);
        this.buffedBlazeChance = config.getDouble("events.blaze_surge.buffed_blaze_chance", 0.3);
    }

    @Override
    public String getId() {
        return "blaze_surge";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.blaze_surge.display_name", "&6Blaze Surge");
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
     * 仅当有玩家位于下界、且通过基础概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((player) -> player.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始时向下界玩家发送标题提示。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.blaze_surge.title"), this.plugin.lang.tr(player, "event.blaze_surge.subtitle"), 20, 80, 40);
            }
        }

    }

    /**
     * 事件结束时向下界玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.blaze_surge.end"));
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
     * 监听生物生成：下界自然生成烈焰人时，按概率强化其属性。
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Blaze blaze) {
            if (blaze.getWorld().getEnvironment() == Environment.NETHER) {
                if (!(Math.random() > this.buffedBlazeChance)) {
                    blaze.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 0, true, false, true));
                    blaze.setGlowing(true);
                    AttributeInstance maxHp = blaze.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHp != null) {
                        maxHp.setBaseValue(Math.max(maxHp.getBaseValue(), (double) 24.0F));
                        blaze.setHealth(Math.min(blaze.getHealth(), (double) ((float) maxHp.getBaseValue())));
                    }

                }
            }
        }
    }

    /**
     * 监听烈焰人死亡：玩家击杀时按概率额外掉落烈焰棒并播放火焰粒子。
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Blaze blaze) {
            if (blaze.getWorld().getEnvironment() == Environment.NETHER) {
                if (blaze.getKiller() != null) {
                    if (!(Math.random() > this.extraRodChance)) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        int amt = this.extraRodMax <= this.extraRodMin ? this.extraRodMin : rnd.nextInt(this.extraRodMin, this.extraRodMax + 1);
                        event.getDrops().add(new ItemStack(Material.BLAZE_ROD, Math.max(1, amt)));
                        blaze.getWorld().spawnParticle(Particle.FLAME, blaze.getLocation().add((double) 0.0F, (double) 1.0F, (double) 0.0F), 12, 0.3, 0.4, 0.3, 0.01);
                    }
                }
            }
        }
    }
}
