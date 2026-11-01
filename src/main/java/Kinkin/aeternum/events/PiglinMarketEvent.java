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

public final class PiglinMarketEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double bonusChance;
    private double duplicateOutcomeChance;

    public PiglinMarketEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.piglin_market.enabled", true);
        this.minDur = y.getInt("events.piglin_market.min_duration_days", 1);
        this.maxDur = y.getInt("events.piglin_market.max_duration_days", 1);
        this.baseChance = y.getDouble("events.piglin_market.base_chance_per_day", 0.08);
        this.bonusChance = y.getDouble("events.piglin_market.bonus_item_chance", 0.35);
        this.duplicateOutcomeChance = y.getDouble("events.piglin_market.duplicate_outcome_chance", 0.18);
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
                p.sendTitle(this.plugin.lang.tr(p, "event.piglin_market.title"), this.plugin.lang.tr(p, "event.piglin_market.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.piglin_market.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onBarter(PiglinBarterEvent e) {
        Piglin piglin = e.getEntity();
        if (piglin != null) {
            World w = piglin.getWorld();
            if (w.getEnvironment() == Environment.NETHER) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                List<ItemStack> out = e.getOutcome();
                if (out != null && !out.isEmpty()) {
                    if (rnd.nextDouble() < this.duplicateOutcomeChance) {
                        ItemStack pick = (ItemStack)out.get(rnd.nextInt(out.size()));
                        if (pick != null && pick.getType() != Material.AIR) {
                            out.add(pick.clone());
                        }
                    }

                    if (rnd.nextDouble() < this.bonusChance) {
                        out.add(this.rollMarketBonus(rnd));
                    }

                }
            }
        }
    }

    private ItemStack rollMarketBonus(ThreadLocalRandom rnd) {
        int roll = rnd.nextInt(100);
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
