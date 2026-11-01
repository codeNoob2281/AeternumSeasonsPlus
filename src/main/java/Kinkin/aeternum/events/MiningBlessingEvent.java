package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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

public final class MiningBlessingEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private int maxPerYear;
    private double extraOreChance;
    private double expBoostChance;
    private double rareBonusChance;
    private final Map<Integer, Integer> usedPerYear = new HashMap();
    private final Set<Material> oreTypes;

    public MiningBlessingEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.oreTypes = EnumSet.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.mining.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = y.getInt("events.mining.min_duration_days", 2);
        this.maxDur = y.getInt("events.mining.max_duration_days", 3);
        this.baseChance = y.getDouble("events.mining.base_chance_per_day", 0.12);
        this.maxPerYear = y.getInt("events.mining.max_per_year", 3);
        this.extraOreChance = y.getDouble("events.mining.extra_ore_chance", 0.35);
        this.expBoostChance = y.getDouble("events.mining.exp_boost_chance", 0.4);
        this.rareBonusChance = y.getDouble("events.mining.rare_bonus_chance", 0.05);
    }

    public String getId() {
        return "mining_blessing";
    }

    public String getDisplayName() {
        return "Mining Blessing";
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

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else {
            int used = (Integer)this.usedPerYear.getOrDefault(st.year, 0);
            if (used >= this.maxPerYear) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        this.usedPerYear.put(st.year, (Integer)this.usedPerYear.getOrDefault(st.year, 0) + 1);

        for(Player p : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(p, "event.mining.title");
            String sub = this.plugin.lang.tr(p, "event.mining.subtitle");
            p.sendTitle(title, sub, 20, 80, 40);
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.mining.end"));
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!e.isCancelled()) {
            Block b = e.getBlock();
            if (this.oreTypes.contains(b.getType())) {
                Player p = e.getPlayer();
                if (p.getWorld().getEnvironment() == Environment.NORMAL) {
                    if (this.globalParticlesEnabled) {
                        b.getWorld().spawnParticle(Particle.CRIT, b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 8, 0.2, 0.2, 0.2, 0.01);
                    }

                    ItemStack tool = p.getInventory().getItemInMainHand();
                    if (Math.random() < this.extraOreChance) {
                        ItemStack extra = this.getPrimaryDrop(b, tool, p);
                        if (extra != null) {
                            extra.setAmount(1);
                            b.getWorld().dropItemNaturally(b.getLocation(), extra);
                        }
                    }

                    if (Math.random() < this.rareBonusChance) {
                        ItemStack bonus = this.getRareBonusDrop(b);
                        if (bonus != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), bonus);
                        }
                    }

                    if (Math.random() < this.expBoostChance) {
                        int extraXp = 2 + (new Random()).nextInt(4);
                        e.setExpToDrop(e.getExpToDrop() + extraXp);
                    }

                }
            }
        }
    }

    private ItemStack getPrimaryDrop(Block b, ItemStack tool, Player p) {
        Collection<ItemStack> drops;
        if (tool != null) {
            try {
                drops = b.getDrops(tool, p);
            } catch (Throwable var7) {
                drops = b.getDrops(tool);
            }
        } else {
            drops = b.getDrops();
        }

        for(ItemStack it : drops) {
            if (it != null && it.getType() != Material.AIR && it.getAmount() > 0) {
                return it.clone();
            }
        }

        Material m = b.getType();
        Material result;
        switch (m) {
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

    private ItemStack getRareBonusDrop(Block b) {
        Material type = b.getType();
        return type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE ? new ItemStack(Material.RAW_IRON, 1) : new ItemStack(Material.DIAMOND, 1);
    }
}
