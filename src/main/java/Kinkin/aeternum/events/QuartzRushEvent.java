package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 石英热潮事件（Quartz Rush）。
 *
 * <p>当玩家身处下界时，有一定概率触发本事件。事件激活期间，玩家挖掘
 * 下界石英矿石时，有概率额外掉落一批石英，并播放末地烛粒子效果。
 * 配置来自 {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class QuartzRushEvent implements SeasonalEvent {
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
     * 挖掘石英矿石额外掉落石英的概率。
     */
    private double extraQuartzChance;
    /**
     * 额外掉落石英的最少数量。
     */
    private int extraMin;
    /**
     * 额外掉落石英的最多数量。
     */
    private int extraMax;

    public QuartzRushEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.quartz_rush.enabled", true);
        this.minDur = config.getInt("events.quartz_rush.min_duration_days", 1);
        this.maxDur = config.getInt("events.quartz_rush.max_duration_days", 2);
        this.baseChance = config.getDouble("events.quartz_rush.base_chance_per_day", 0.1);
        this.extraQuartzChance = config.getDouble("events.quartz_rush.extra_quartz_chance", 0.55);
        this.extraMin = config.getInt("events.quartz_rush.extra_min", 1);
        this.extraMax = config.getInt("events.quartz_rush.extra_max", 2);
    }

    @Override
    public String getId() {
        return "quartz_rush";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.quartz_rush.display_name", "&fQuartz Rush");
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
     * 事件开始时向所有身处下界的玩家发送标题提示。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.quartz_rush.title"), this.plugin.lang.tr(player, "event.quartz_rush.subtitle"), 20, 80, 40);
            }
        }

    }

    /**
     * 事件结束时通知所有身处下界的玩家事件已结束。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.quartz_rush.end"));
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
     * 监听方块破坏事件：在下界破坏石英矿石时，按概率额外掉落石英。
     */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            Player player = event.getPlayer();
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                Block block = event.getBlock();
                if (block.getType() == Material.NETHER_QUARTZ_ORE) {
                    if (!(Math.random() > this.extraQuartzChance)) {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        int amount = this.extraMax <= this.extraMin ? this.extraMin : random.nextInt(this.extraMin, this.extraMax + 1);
                        ItemStack extraDrop = new ItemStack(Material.QUARTZ, Math.max(1, amount));
                        block.getWorld().dropItemNaturally(block.getLocation(), extraDrop);
                        block.getWorld().spawnParticle(Particle.END_ROD, block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), 6, 0.2, 0.2, 0.2, 0.01);
                    }
                }
            }
        }
    }
}
