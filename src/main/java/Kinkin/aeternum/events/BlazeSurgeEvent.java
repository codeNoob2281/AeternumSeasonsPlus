package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.concurrent.ThreadLocalRandom;
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

public final class BlazeSurgeEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double extraRodChance;
    private int extraRodMin;
    private int extraRodMax;
    private double buffedBlazeChance;

    public BlazeSurgeEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.blaze_surge.enabled", true);
        this.minDur = y.getInt("events.blaze_surge.min_duration_days", 1);
        this.maxDur = y.getInt("events.blaze_surge.max_duration_days", 2);
        this.baseChance = y.getDouble("events.blaze_surge.base_chance_per_day", 0.08);
        this.extraRodChance = y.getDouble("events.blaze_surge.extra_rod_chance", 0.35);
        this.extraRodMin = y.getInt("events.blaze_surge.extra_rod_min", 1);
        this.extraRodMax = y.getInt("events.blaze_surge.extra_rod_max", 2);
        this.buffedBlazeChance = y.getDouble("events.blaze_surge.buffed_blaze_chance", 0.3);
    }

    public String getId() {
        return "blaze_surge";
    }

    public String getDisplayName() {
        return "Blaze Surge";
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
                p.sendTitle(this.plugin.lang.tr(p, "event.blaze_surge.title"), this.plugin.lang.tr(p, "event.blaze_surge.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.blaze_surge.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Blaze blaze) {
            if (blaze.getWorld().getEnvironment() == Environment.NETHER) {
                if (!(Math.random() > this.buffedBlazeChance)) {
                    blaze.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 0, true, false, true));
                    blaze.setGlowing(true);
                    AttributeInstance maxHp = blaze.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHp != null) {
                        maxHp.setBaseValue(Math.max(maxHp.getBaseValue(), (double)24.0F));
                        blaze.setHealth(Math.min(blaze.getHealth(), (double)((float)maxHp.getBaseValue())));
                    }

                }
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Blaze blaze) {
            if (blaze.getWorld().getEnvironment() == Environment.NETHER) {
                if (blaze.getKiller() != null) {
                    if (!(Math.random() > this.extraRodChance)) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        int amt = this.extraRodMax <= this.extraRodMin ? this.extraRodMin : rnd.nextInt(this.extraRodMin, this.extraRodMax + 1);
                        e.getDrops().add(new ItemStack(Material.BLAZE_ROD, Math.max(1, amt)));
                        blaze.getWorld().spawnParticle(Particle.FLAME, blaze.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 12, 0.3, 0.4, 0.3, 0.01);
                    }
                }
            }
        }
    }
}
