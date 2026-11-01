package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.Collection;
import java.util.EnumSet;
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

public final class FungusBloomEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double extraDropChance;
    private final Set<Material> boosted;

    public FungusBloomEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.boosted = EnumSet.of(Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT);
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.fungus_bloom.enabled", true);
        this.minDur = y.getInt("events.fungus_bloom.min_duration_days", 1);
        this.maxDur = y.getInt("events.fungus_bloom.max_duration_days", 2);
        this.baseChance = y.getDouble("events.fungus_bloom.base_chance_per_day", 0.1);
        this.extraDropChance = y.getDouble("events.fungus_bloom.extra_drop_chance", 0.55);
    }

    public String getId() {
        return "fungus_bloom";
    }

    public String getDisplayName() {
        return "Fungus Bloom";
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
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((p) -> p.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendTitle(this.plugin.lang.tr(p, "event.fungus_bloom.title"), this.plugin.lang.tr(p, "event.fungus_bloom.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.fungus_bloom.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!e.isCancelled()) {
            Player p = e.getPlayer();
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                Block b = e.getBlock();
                if (this.boosted.contains(b.getType())) {
                    if (!(Math.random() > this.extraDropChance)) {
                        ItemStack tool = p.getInventory().getItemInMainHand();
                        ItemStack extra = this.getPrimaryDrop(b, tool, p);
                        if (extra != null) {
                            extra.setAmount(Math.max(1, extra.getAmount()));
                            extra.setAmount(1);
                            b.getWorld().dropItemNaturally(b.getLocation(), extra);
                            b.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 6, 0.3, 0.3, 0.3, 0.01);
                        }
                    }
                }
            }
        }
    }

    private ItemStack getPrimaryDrop(Block b, ItemStack tool, Player p) {
        Collection<ItemStack> drops;
        try {
            drops = tool != null ? b.getDrops(tool, p) : b.getDrops();
        } catch (Throwable var7) {
            drops = tool != null ? b.getDrops(tool) : b.getDrops();
        }

        for(ItemStack it : drops) {
            if (it != null && it.getType() != Material.AIR && it.getAmount() > 0) {
                return it.clone();
            }
        }

        return null;
    }
}
