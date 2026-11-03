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

import java.util.*;

/**
 * 采矿祝福事件（Mining Blessing）。
 *
 * <p>每年最多触发若干次。事件期间，玩家在主世界破坏矿石时有几率获得：
 * <ul>
 *     <li>额外的矿石主产物；</li>
 *     <li>稀有额外掉落（钻石矿给钻石，其它给粗铁）；</li>
 *     <li>额外的经验掉落。</li>
 * </ul>
 * 触发额外矿石时会播放暴击粒子。</p>
 */
public final class MiningBlessingEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 是否启用全局粒子特效。
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
     * 每年最多触发次数。
     */
    private int maxPerYear;
    /**
     * 额外矿石掉落概率。
     */
    private double extraOreChance;
    /**
     * 额外经验掉落概率。
     */
    private double expBoostChance;
    /**
     * 稀有额外掉落概率。
     */
    private double rareBonusChance;
    /**
     * 记录每年已触发次数。
     */
    private final Map<Integer, Integer> usedPerYear = new HashMap();
    /**
     * 受本事件加成的矿石材质集合。
     */
    private final Set<Material> oreTypes;

    public MiningBlessingEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.oreTypes = EnumSet.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.mining.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = config.getInt("events.mining.min_duration_days", 2);
        this.maxDur = config.getInt("events.mining.max_duration_days", 3);
        this.baseChance = config.getDouble("events.mining.base_chance_per_day", 0.12);
        this.maxPerYear = config.getInt("events.mining.max_per_year", 3);
        this.extraOreChance = config.getDouble("events.mining.extra_ore_chance", 0.35);
        this.expBoostChance = config.getDouble("events.mining.exp_boost_chance", 0.4);
        this.rareBonusChance = config.getDouble("events.mining.rare_bonus_chance", 0.05);
    }

    @Override
    public String getId() {
        return "mining_blessing";
    }

    @Override
    public String getDisplayName() {
        return "Mining Blessing";
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
     * 判定是否可开始：已启用、本年次数未超上限且通过基础概率。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            int used = (Integer) this.usedPerYear.getOrDefault(state.year, 0);
            if (used >= this.maxPerYear) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始：累计本年触发次数，并向全服玩家发送标题。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.usedPerYear.put(state.year, (Integer) this.usedPerYear.getOrDefault(state.year, 0) + 1);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(player, "event.mining.title");
            String sub = this.plugin.lang.tr(player, "event.mining.subtitle");
            player.sendTitle(title, sub, 20, 80, 40);
        }

    }

    /**
     * 事件结束：向全服玩家发送结束提示。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.mining.end"));
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听方块破坏：主世界破坏矿石时按概率给予额外矿石、稀有掉落与额外经验。
     */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            Block block = event.getBlock();
            if (this.oreTypes.contains(block.getType())) {
                Player player = event.getPlayer();
                if (player.getWorld().getEnvironment() == Environment.NORMAL) {
                    if (this.globalParticlesEnabled) {
                        block.getWorld().spawnParticle(Particle.CRIT, block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), 8, 0.2, 0.2, 0.2, 0.01);
                    }

                    ItemStack tool = player.getInventory().getItemInMainHand();
                    if (Math.random() < this.extraOreChance) {
                        ItemStack extra = this.getPrimaryDrop(block, tool, player);
                        if (extra != null) {
                            extra.setAmount(1);
                            block.getWorld().dropItemNaturally(block.getLocation(), extra);
                        }
                    }

                    if (Math.random() < this.rareBonusChance) {
                        ItemStack bonus = this.getRareBonusDrop(block);
                        if (bonus != null) {
                            block.getWorld().dropItemNaturally(block.getLocation(), bonus);
                        }
                    }

                    if (Math.random() < this.expBoostChance) {
                        int extraXp = 2 + (new Random()).nextInt(4);
                        event.setExpToDrop(event.getExpToDrop() + extraXp);
                    }

                }
            }
        }
    }

    /**
     * 获取方块被工具破坏时应掉落的主产物副本；无法获取时按材质查表兜底。
     */
    private ItemStack getPrimaryDrop(Block block, ItemStack tool, Player player) {
        Collection<ItemStack> drops;
        if (tool != null) {
            try {
                drops = block.getDrops(tool, player);
            } catch (Throwable ignored) {
                drops = block.getDrops(tool);
            }
        } else {
            drops = block.getDrops();
        }

        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                return drop.clone();
            }
        }

        Material type = block.getType();
        Material result;
        switch (type) {
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE:
                result = Material.COAL;
                break;
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE:
                result = Material.RAW_IRON;
                break;
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                result = Material.RAW_COPPER;
                break;
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE:
                result = Material.RAW_GOLD;
                break;
            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE:
                result = Material.LAPIS_LAZULI;
                break;
            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE:
                result = Material.REDSTONE;
                break;
            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
                result = Material.DIAMOND;
                break;
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
                result = Material.EMERALD;
                break;
            default:
                return null;
        }

        return new ItemStack(result, 1);
    }

    /**
     * 生成稀有额外掉落的物品（钻石矿给钻石，其它给粗铁）。
     */
    private ItemStack getRareBonusDrop(Block block) {
        Material type = block.getType();
        return type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE ? new ItemStack(Material.RAW_IRON, 1) : new ItemStack(Material.DIAMOND, 1);
    }
}
