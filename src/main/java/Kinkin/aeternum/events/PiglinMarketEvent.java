package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 猪灵集市事件（Piglin Market）。
 *
 * <p>当玩家身处下界时，有一定概率触发本事件。事件激活期间，猪灵以物易物
 * （barter）时会获得额外收益：
 * <ul>
 *     <li>有概率把交易结果中的某件物品再复制一份；</li>
 *     <li>有概率额外追加一件“集市奖励”物品（金粒、黑曜石、光灵箭等）。</li>
 * </ul>
 * 配置来自 {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class PiglinMarketEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /** 事件最短持续天数。 */
    private int minDur;
    /** 事件最长持续天数。 */
    private int maxDur;
    /** 每天触发事件的基础概率。 */
    private double baseChance;
    /** 额外追加集市奖励物品的概率。 */
    private double bonusChance;
    /** 复制一件原交易结果的概率。 */
    private double duplicateOutcomeChance;

    public PiglinMarketEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /** 从 events.yml 重新读取本事件的配置项。 */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.piglin_market.enabled", true);
        this.minDur = config.getInt("events.piglin_market.min_duration_days", 1);
        this.maxDur = config.getInt("events.piglin_market.max_duration_days", 1);
        this.baseChance = config.getDouble("events.piglin_market.base_chance_per_day", 0.08);
        this.bonusChance = config.getDouble("events.piglin_market.bonus_item_chance", 0.35);
        this.duplicateOutcomeChance = config.getDouble("events.piglin_market.duplicate_outcome_chance", 0.18);
    }

    public String getId() {
        return "piglin_market";
    }

    public String getDisplayName() {
        return "Piglin Market";
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

    /** 仅当有玩家位于下界、且通过基础概率判定时才允许开始。 */
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

    /** 事件开始时向所有身处下界的玩家发送标题提示。 */
    public void onStart(CalendarState state, EventContext context) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.piglin_market.title"), this.plugin.lang.tr(player, "event.piglin_market.subtitle"), 20, 80, 40);
            }
        }

    }

    /** 事件结束时通知所有身处下界的玩家事件已结束。 */
    public void onEnd(CalendarState state, EventContext context) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.piglin_market.end"));
            }
        }

    }

    public void onDayTick(CalendarState state, EventContext context) {
    }

    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听猪灵以物易物（barter）事件：在下界交易时，按概率复制一件结果物品，
     * 并按概率追加一件集市奖励物品。
     */
    @EventHandler
    public void onBarter(PiglinBarterEvent event) {
        Piglin piglin = event.getEntity();
        if (piglin != null) {
            World world = piglin.getWorld();
            if (world.getEnvironment() == Environment.NETHER) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                List<ItemStack> outcomes = event.getOutcome();
                if (outcomes != null && !outcomes.isEmpty()) {
                    if (random.nextDouble() < this.duplicateOutcomeChance) {
                        ItemStack pick = (ItemStack)outcomes.get(random.nextInt(outcomes.size()));
                        if (pick != null && pick.getType() != Material.AIR) {
                            outcomes.add(pick.clone());
                        }
                    }

                    if (random.nextDouble() < this.bonusChance) {
                        outcomes.add(this.rollMarketBonus(random));
                    }

                }
            }
        }
    }

    /** 按权重（百分位区间）随机挑选一件集市奖励物品。 */
    private ItemStack rollMarketBonus(ThreadLocalRandom random) {
        int roll = random.nextInt(100);
        if (roll < 40) {
            return new ItemStack(Material.GOLD_NUGGET, 8);
        } else if (roll < 60) {
            return new ItemStack(Material.OBSIDIAN, 2);
        } else if (roll < 75) {
            return new ItemStack(Material.SPECTRAL_ARROW, 8);
        } else if (roll < 88) {
            return new ItemStack(Material.FIRE_CHARGE, 1);
        } else {
            return roll < 96 ? new ItemStack(Material.ENDER_PEARL, 1) : new ItemStack(Material.CRYING_OBSIDIAN, 1);
        }
    }
}
