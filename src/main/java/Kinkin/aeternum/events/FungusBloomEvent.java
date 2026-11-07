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

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * 菌潮绽放事件（Fungus Bloom）。
 *
 * <p>当有玩家身处下界时，有一定概率触发本事件。事件期间，玩家在下界破坏
 * 下界植物类方块（菌类、菌索、藤蔓等）时，有概率额外掉落一份主要产物，
 * 并伴随孢子粒子效果。</p>
 */
public final class FungusBloomEvent implements SeasonalEvent {
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
     * 破坏受加成方块时额外掉落的概率。
     */
    private double extraDropChance;
    /**
     * 受本事件加成的下界植物材质集合。
     */
    private final Set<Material> boosted;

    public FungusBloomEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.boosted = EnumSet.of(Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT);
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.fungus_bloom.enabled", true);
        this.minDur = config.getInt("events.fungus_bloom.min_duration_days", 1);
        this.maxDur = config.getInt("events.fungus_bloom.max_duration_days", 2);
        this.baseChance = config.getDouble("events.fungus_bloom.base_chance_per_day", 0.1);
        this.extraDropChance = config.getDouble("events.fungus_bloom.extra_drop_chance", 0.55);
    }

    @Override
    public String getId() {
        return "fungus_bloom";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.fungus_bloom.display_name", "&dFungus Bloom");
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
                player.sendTitle(this.plugin.lang.tr(player, "event.fungus_bloom.title"), this.plugin.lang.tr(player, "event.fungus_bloom.subtitle"), 20, 80, 40);
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
                player.sendMessage(this.plugin.lang.tr(player, "event.fungus_bloom.end"));
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
     * 监听方块破坏：下界破坏受加成植物时按概率额外掉落主要产物。
     */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            Player player = event.getPlayer();
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                Block block = event.getBlock();
                if (this.boosted.contains(block.getType())) {
                    if (!(Math.random() > this.extraDropChance)) {
                        ItemStack tool = player.getInventory().getItemInMainHand();
                        ItemStack extra = this.getPrimaryDrop(block, tool, player);
                        if (extra != null) {
                            extra.setAmount(Math.max(1, extra.getAmount()));
                            extra.setAmount(1);
                            block.getWorld().dropItemNaturally(block.getLocation(), extra);
                            block.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), 6, 0.3, 0.3, 0.3, 0.01);
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取方块被工具破坏时应掉落的主产物副本（优先按工具/玩家计算真实掉落）。
     */
    private ItemStack getPrimaryDrop(Block block, ItemStack tool, Player player) {
        Collection<ItemStack> drops;
        try {
            drops = tool != null ? block.getDrops(tool, player) : block.getDrops();
        } catch (Throwable ignored) {
            drops = tool != null ? block.getDrops(tool) : block.getDrops();
        }

        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                return drop.clone();
            }
        }

        return null;
    }
}
