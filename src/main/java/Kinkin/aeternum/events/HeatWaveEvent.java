package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class HeatWaveEvent implements SeasonalEvent {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private int maxPerSummer;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private boolean damageExposed;
    private double tickDamage;
    private boolean applyWeakness;
    private int minArmorProtection;
    private final Map<String, Integer> timesPerSummerKey = new HashMap();

    public HeatWaveEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.heat_wave.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.maxPerSummer = y.getInt("events.heat_wave.max_per_summer", 3);
        this.minDur = y.getInt("events.heat_wave.min_duration_days", 1);
        this.maxDur = y.getInt("events.heat_wave.max_duration_days", 2);
        this.baseChance = y.getDouble("events.heat_wave.base_chance_per_day", 0.12);
        this.damageExposed = y.getBoolean("events.heat_wave.damage_exposed", true);
        this.tickDamage = y.getDouble("events.heat_wave.tick_damage", (double)0.5F);
        this.applyWeakness = y.getBoolean("events.heat_wave.apply_weakness", true);
        this.minArmorProtection = y.getInt("events.heat_wave.min_armor_protection", 2);
    }

    public String getId() {
        return "heat_wave";
    }

    public String getDisplayName() {
        return "Heat Wave";
    }

    public boolean isSeasonAllowed(Season season) {
        return season == Season.SUMMER;
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
        } else if (st.season != Season.SUMMER) {
            return false;
        } else {
            int var10000 = st.year;
            String key = var10000 + "_" + st.season.name();
            int used = (Integer)this.timesPerSummerKey.getOrDefault(key, 0);
            if (used >= this.maxPerSummer) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        int var10000 = st.year;
        String key = var10000 + "_" + st.season.name();
        this.timesPerSummerKey.put(key, (Integer)this.timesPerSummerKey.getOrDefault(key, 0) + 1);

        for(World w : ctx.overworlds()) {
            if (!w.getName().equalsIgnoreCase("aeternum_frost")) {
                w.playSound(w.getSpawnLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.7F, 1.0F);
            }
        }

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equalsIgnoreCase("aeternum_frost")) {
                String title = this.plugin.lang.tr(p, "event.heat_wave.title");
                String sub = this.plugin.lang.tr(p, "event.heat_wave.subtitle");
                p.sendTitle(title, sub, 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equalsIgnoreCase("aeternum_frost")) {
                p.sendMessage(this.plugin.lang.tr(p, "event.heat_wave.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        if (this.damageExposed || this.applyWeakness) {
            for(Player p : Bukkit.getOnlinePlayers()) {
                World pw = p.getWorld();
                if (pw.getEnvironment() == Environment.NORMAL && !pw.getName().equalsIgnoreCase("aeternum_frost") && !p.isDead() && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR && !this.isInShade(p)) {
                    int armorScore = this.countArmorPieces(p);
                    if (this.applyWeakness) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, false, true));
                    }

                    if (this.damageExposed && armorScore < this.minArmorProtection) {
                        p.damage(this.tickDamage);
                        if (this.globalParticlesEnabled) {
                            p.spawnParticle(Particle.FLAME, p.getLocation().add((double)0.0F, 1.2, (double)0.0F), 4, 0.2, 0.3, 0.2, 0.01);
                        }
                    }
                }
            }

        }
    }

    private boolean isInShade(Player p) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        int x = loc.getBlockX();
        int y = loc.getBlockY() + 1;
        int z = loc.getBlockZ();
        int highestY = w.getHighestBlockYAt(x, z);
        return highestY > y;
    }

    private int countArmorPieces(Player p) {
        int c = 0;
        if (p.getInventory().getHelmet() != null) {
            ++c;
        }

        if (p.getInventory().getChestplate() != null) {
            ++c;
        }

        if (p.getInventory().getLeggings() != null) {
            ++c;
        }

        if (p.getInventory().getBoots() != null) {
            ++c;
        }

        return c;
    }
}
