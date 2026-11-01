package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.concurrent.ThreadLocalRandom;
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

public final class QuartzRushEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double extraQuartzChance;
    private int extraMin;
    private int extraMax;

    public QuartzRushEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.quartz_rush.enabled", true);
        this.minDur = y.getInt("events.quartz_rush.min_duration_days", 1);
        this.maxDur = y.getInt("events.quartz_rush.max_duration_days", 2);
        this.baseChance = y.getDouble("events.quartz_rush.base_chance_per_day", 0.1);
        this.extraQuartzChance = y.getDouble("events.quartz_rush.extra_quartz_chance", 0.55);
        this.extraMin = y.getInt("events.quartz_rush.extra_min", 1);
        this.extraMax = y.getInt("events.quartz_rush.extra_max", 2);
    }

    public String getId() {
        return "quartz_rush";
    }

    public String getDisplayName() {
        return "Quartz Rush";
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
                p.sendTitle(this.plugin.lang.tr(p, "event.quartz_rush.title"), this.plugin.lang.tr(p, "event.quartz_rush.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.quartz_rush.end"));
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
                if (b.getType() == Material.NETHER_QUARTZ_ORE) {
                    if (!(Math.random() > this.extraQuartzChance)) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        int amt = this.extraMax <= this.extraMin ? this.extraMin : rnd.nextInt(this.extraMin, this.extraMax + 1);
                        ItemStack extra = new ItemStack(Material.QUARTZ, Math.max(1, amt));
                        b.getWorld().dropItemNaturally(b.getLocation(), extra);
                        b.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 6, 0.2, 0.2, 0.2, 0.01);
                    }
                }
            }
        }
    }
}
