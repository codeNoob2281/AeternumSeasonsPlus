package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 岩浆潮汐事件（Magma Tides）。
 *
 * <p>当有玩家身处下界时，有一定概率触发本事件。事件期间：
 * <ul>
 *     <li>下界自然生成的岩浆怪有概率变大一级；</li>
 *     <li>玩家击杀岩浆怪时有概率额外掉落岩浆膏。</li>
 * </ul>
 */
public final class MagmaTidesEvent implements SeasonalEvent {
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
     * 岩浆怪变大一级的概率。
     */
    private double biggerCubeChance;
    /**
     * 击杀岩浆怪额外掉落岩浆膏的概率。
     */
    private double extraCreamChance;

    public MagmaTidesEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.magma_tides.enabled", true);
        this.minDur = config.getInt("events.magma_tides.min_duration_days", 1);
        this.maxDur = config.getInt("events.magma_tides.max_duration_days", 2);
        this.baseChance = config.getDouble("events.magma_tides.base_chance_per_day", 0.1);
        this.biggerCubeChance = config.getDouble("events.magma_tides.bigger_cube_chance", 0.3);
        this.extraCreamChance = config.getDouble("events.magma_tides.extra_cream_chance", 0.4);
    }

    @Override
    public String getId() {
        return "magma_tides";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.magma_tides.display_name", "&cMagma Tides");
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
                player.sendTitle(this.plugin.lang.tr(player, "event.magma_tides.title"), this.plugin.lang.tr(player, "event.magma_tides.subtitle"), 20, 80, 40);
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
                player.sendMessage(this.plugin.lang.tr(player, "event.magma_tides.end"));
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
     * 监听生物生成：下界岩浆怪按概率变大一级（不超过 4）。
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof MagmaCube cube) {
            if (cube.getWorld().getEnvironment() == Environment.NETHER) {
                if (!(Math.random() > this.biggerCubeChance)) {
                    int size = cube.getSize();
                    cube.setSize(Math.min(4, size + 1));
                    cube.getWorld().spawnParticle(Particle.LAVA, cube.getLocation().add((double) 0.0F, 0.8, (double) 0.0F), 10, 0.4, 0.3, 0.4, 0.01);
                }
            }
        }
    }

    /**
     * 监听岩浆怪死亡：玩家击杀时按概率额外掉落岩浆膏。
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof MagmaCube cube) {
            if (cube.getWorld().getEnvironment() == Environment.NETHER) {
                if (cube.getKiller() != null) {
                    if (!(Math.random() > this.extraCreamChance)) {
                        int amt = 1 + ThreadLocalRandom.current().nextInt(2);
                        event.getDrops().add(new ItemStack(Material.MAGMA_CREAM, amt));
                    }
                }
            }
        }
    }
}
