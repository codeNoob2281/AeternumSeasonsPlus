package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class NewYearEvent implements SeasonalEvent {
    private static final String OVERWORLD_CHANNEL_PATH = "calendar.channels.OVERWORLD.months";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private double fireworksChancePerPlayer;
    private int fireworksBurstsPerWave;
    private int giftRocketsMin;
    private int giftRocketsMax;
    private int giftGunpowderMin;
    private int giftGunpowderMax;
    private int fireworksSecondsLeft = 0;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();

    public NewYearEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.new_year.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.fireworksChancePerPlayer = y.getDouble("events.new_year.fireworks_chance", 0.6);
        this.fireworksBurstsPerWave = y.getInt("events.new_year.fireworks_bursts", 3);
        this.giftRocketsMin = y.getInt("events.new_year.gift_rockets_min", 12);
        this.giftRocketsMax = y.getInt("events.new_year.gift_rockets_max", 24);
        this.giftGunpowderMin = y.getInt("events.new_year.gift_gunpowder_min", 6);
        this.giftGunpowderMax = y.getInt("events.new_year.gift_gunpowder_max", 12);
    }

    public String getId() {
        return "new_year";
    }

    public String getDisplayName() {
        return "Año Nuevo";
    }

    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    public int getMinDurationDays() {
        return 1;
    }

    public int getMaxDurationDays() {
        return 1;
    }

    private boolean isNewYear(CalendarState st) {
        if (st == null) {
            return false;
        } else if (!st.monthsEnabled) {
            return st.season == Season.SPRING && st.day == 1;
        } else {
            String firstMonthId = this.getFirstConfiguredMonthId();
            if (firstMonthId != null && st.monthId != null && !st.monthId.isBlank()) {
                return st.day == 1 && firstMonthId.equalsIgnoreCase(st.monthId);
            } else {
                return false;
            }
        }
    }

    private String getFirstConfiguredMonthId() {
        ConfigurationSection monthsSec = this.plugin.cfg.calendar.getConfigurationSection("calendar.channels.OVERWORLD.months");
        if (monthsSec != null && monthsSec.getBoolean("enabled", false)) {
            List<String> order = monthsSec.getStringList("order");
            if (order != null && !order.isEmpty()) {
                for(String id : order) {
                    if (id != null && !id.isBlank()) {
                        return id.trim();
                    }
                }

                return null;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        return !this.enabled ? false : this.isNewYear(st);
    }

    public void onStart(CalendarState st, EventContext ctx) {
        for(World w : ctx.overworlds()) {
            for(Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR) {
                    String title = this.plugin.lang.tr(p, "event.new_year.title");
                    String sub = this.plugin.lang.tr(p, "event.new_year.subtitle");
                    p.sendTitle(title, sub, 20, 80, 40);
                    this.giveGift(p);
                }
            }
        }

        this.fireworksSecondsLeft = 60;
    }

    public void onEnd(CalendarState st, EventContext ctx) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.new_year.end"));
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        if (this.fireworksSecondsLeft > 0) {
            --this.fireworksSecondsLeft;

            for(World world : ctx.overworlds()) {
                for(Player player : world.getPlayers()) {
                    if (player.getGameMode() != GameMode.SPECTATOR && !(this.rnd.nextDouble() > this.fireworksChancePerPlayer)) {
                        this.spawnFireworksAroundPlayer(player, world);
                    }
                }
            }

        }
    }

    private void giveGift(Player p) {
        int rockets = this.randomBetween(this.giftRocketsMin, this.giftRocketsMax);
        int powder = this.randomBetween(this.giftGunpowderMin, this.giftGunpowderMax);
        if (rockets > 0) {
            p.getInventory().addItem(new ItemStack[]{new ItemStack(Material.FIREWORK_ROCKET, rockets)});
        }

        if (powder > 0) {
            p.getInventory().addItem(new ItemStack[]{new ItemStack(Material.GUNPOWDER, powder)});
        }

        p.sendMessage(this.plugin.lang.tr(p, "event.new_year.gift_found"));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
    }

    private int randomBetween(int min, int max) {
        return max <= min ? min : this.rnd.nextInt(min, max + 1);
    }

    private void spawnFireworksAroundPlayer(Player p, World w) {
        Location base = p.getLocation();

        for(int i = 0; i < this.fireworksBurstsPerWave; ++i) {
            int dx = this.rnd.nextInt(-12, 13);
            int dz = this.rnd.nextInt(-12, 13);
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            Block top = w.getHighestBlockAt(x, z);
            Location loc = top.getLocation().add((double)0.5F, (double)6.0F + (double)this.rnd.nextInt(4), (double)0.5F);
            if (this.globalParticlesEnabled) {
                w.spawnParticle(Particle.FIREWORK, loc, 40, (double)0.5F, (double)1.0F, (double)0.5F, 0.01);
                w.spawnParticle(Particle.EXPLOSION, loc, 1, (double)0.0F, (double)0.0F, (double)0.0F, (double)0.0F);
                w.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.6F, 1.2F);
                w.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9F, 1.0F);
            }
        }

    }
}
