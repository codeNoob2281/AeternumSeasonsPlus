package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class WinterFreezeEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private int maxPerWinter;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double damagePerTick;
    private int minArmorPieces;
    private int heatRadius;
    private Set<Material> heatBlocks;
    private final Map<String, Integer> timesPerWinterKey = new HashMap();

    public WinterFreezeEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.winter_freeze.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.maxPerWinter = y.getInt("events.winter_freeze.max_per_winter", 1);
        this.minDur = y.getInt("events.winter_freeze.min_duration_days", 4);
        this.maxDur = y.getInt("events.winter_freeze.max_duration_days", 7);
        this.baseChance = y.getDouble("events.winter_freeze.base_chance_per_day", 0.15);
        this.damagePerTick = y.getDouble("events.winter_freeze.damage_per_tick", (double)0.5F);
        this.minArmorPieces = y.getInt("events.winter_freeze.min_armor_pieces", 2);
        this.heatRadius = y.getInt("events.winter_freeze.heat_radius_blocks", 5);
        this.heatBlocks = new HashSet();

        for(String s : y.getStringList("events.winter_freeze.heat_block_types")) {
            try {
                this.heatBlocks.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException var5) {
            }
        }

        if (this.heatBlocks.isEmpty()) {
            this.heatBlocks.add(Material.CAMPFIRE);
            this.heatBlocks.add(Material.SOUL_CAMPFIRE);
            this.heatBlocks.add(Material.TORCH);
            this.heatBlocks.add(Material.LANTERN);
            this.heatBlocks.add(Material.FIRE);
            this.heatBlocks.add(Material.SOUL_TORCH);
        }

    }

    public String getId() {
        return "winter_freeze";
    }

    public String getDisplayName() {
        return "Winter Freeze";
    }

    public boolean isSeasonAllowed(Season season) {
        return season == Season.WINTER;
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
        } else if (st.season != Season.WINTER) {
            return false;
        } else {
            int var10000 = st.year;
            String key = var10000 + "_" + st.season.name();
            int used = (Integer)this.timesPerWinterKey.getOrDefault(key, 0);
            if (used >= this.maxPerWinter) {
                return false;
            } else {
                int daysPerSeason = ctx.seasons().getDaysPerSeason();
                int minStartDay = Math.max(2, daysPerSeason / 3);
                if (st.day < minStartDay) {
                    return false;
                } else {
                    return Math.random() < this.baseChance;
                }
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        int var10000 = st.year;
        String key = var10000 + "_" + st.season.name();
        this.timesPerWinterKey.put(key, (Integer)this.timesPerWinterKey.getOrDefault(key, 0) + 1);

        for(World w : ctx.overworlds()) {
            w.playSound(w.getSpawnLocation(), Sound.WEATHER_RAIN_ABOVE, 0.6F, 0.5F);
        }

        for(Player p : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(p, "event.winter_freeze.title");
            String sub = this.plugin.lang.tr(p, "event.winter_freeze.subtitle");
            p.sendTitle(title, sub, 20, 80, 40);
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.winter_freeze.end"));
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NORMAL && !p.isDead() && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR && !this.isNearHeat(p)) {
                int armorScore = this.countArmorPieces(p);
                if (armorScore >= this.minArmorPieces) {
                    this.maybeApplyLightChill(p);
                } else {
                    this.applyFreezeDamage(p);
                }
            }
        }

    }

    private void applyFreezeDamage(Player p) {
        if (this.globalParticlesEnabled) {
            p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 10, (double)0.5F, 0.8, (double)0.5F, 0.01);
        }

        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 3, true, false, true));
        if (p.getHealth() > this.damagePerTick + (double)0.5F) {
            p.damage(this.damagePerTick);
        }

        if (p.getTicksLived() % 60 == 0) {
            p.sendMessage(this.plugin.lang.tr(p, "event.winter_freeze.freezing"));
        }

    }

    private void maybeApplyLightChill(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, false, true));
        if (p.getTicksLived() % 100 == 0) {
        }

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

    private boolean isNearHeat(Player p) {
        World w = p.getWorld();
        int px = p.getLocation().getBlockX();
        int py = p.getLocation().getBlockY();
        int pz = p.getLocation().getBlockZ();
        int r = this.heatRadius;

        for(int x = -r; x <= r; ++x) {
            for(int y = -2; y <= 2; ++y) {
                for(int z = -r; z <= r; ++z) {
                    Material m = w.getBlockAt(px + x, py + y, pz + z).getType();
                    if (this.heatBlocks.contains(m)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
