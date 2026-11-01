package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class BloodMoonEvent implements SeasonalEvent, Listener {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private double chancePerNight;
    private int minDay;
    private int maxTimesPerSeason;
    private boolean warpToNight;
    private boolean freezeNight;
    private boolean active = false;
    private final Map<String, Integer> timesPerSeasonKey = new HashMap();
    private static final String TAG = "aet_blood_moon";
    private final NamespacedKey K_NAME;
    private final NamespacedKey K_NAME_VISIBLE;
    private final NamespacedKey K_MAX_HEALTH;
    private final NamespacedKey K_ATTACK_DMG;

    public BloodMoonEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
        this.K_NAME = new NamespacedKey(plugin, "bloodmoon_prev_name");
        this.K_NAME_VISIBLE = new NamespacedKey(plugin, "bloodmoon_prev_name_visible");
        this.K_MAX_HEALTH = new NamespacedKey(plugin, "bloodmoon_prev_max_health");
        this.K_ATTACK_DMG = new NamespacedKey(plugin, "bloodmoon_prev_attack_dmg");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.blood_moon.enabled", true);
        this.chancePerNight = y.getDouble("events.blood_moon.chance_per_night", 0.08);
        this.minDay = y.getInt("events.blood_moon.min_day", 5);
        this.maxTimesPerSeason = y.getInt("events.blood_moon.max_times_per_season", 2);
        this.warpToNight = y.getBoolean("events.blood_moon.world_time_night", false);
        this.freezeNight = y.getBoolean("events.blood_moon.freeze_night", false);
    }

    public String getId() {
        return "blood_moon";
    }

    public String getDisplayName() {
        return "Blood Moon";
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

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else if (st.day < this.minDay) {
            return false;
        } else {
            int var10000 = st.year;
            String key = var10000 + "_" + st.season.name();
            int used = (Integer)this.timesPerSeasonKey.getOrDefault(key, 0);
            if (used >= this.maxTimesPerSeason) {
                return false;
            } else {
                return Math.random() < this.chancePerNight;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        int var10000 = st.year;
        String key = var10000 + "_" + st.season.name();
        this.timesPerSeasonKey.put(key, (Integer)this.timesPerSeasonKey.getOrDefault(key, 0) + 1);
        this.active = true;
        World w = Bukkit.getWorld("world");
        if (w != null) {
            w.setGameRule(GameRule.DO_MOB_SPAWNING, true);
            w.playSound(w.getSpawnLocation(), Sound.AMBIENT_CAVE, 0.8F, 0.5F);

            for(Player p : w.getPlayers()) {
                String title = this.plugin.lang.tr(p, "event.blood_moon.title");
                String sub = this.plugin.lang.tr(p, "event.blood_moon.subtitle");
                p.sendTitle(title, sub, 20, 80, 40);
            }

        }
    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.active = false;
        World w = Bukkit.getWorld("world");
        if (w != null) {
            this.cleanupWorld(w);

            for(Player p : w.getPlayers()) {
                p.sendMessage(this.plugin.lang.tr(p, "event.blood_moon.end"));
            }

        }
    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    private boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000L && t < 24000L;
    }

    @EventHandler
    public void onBed(PlayerBedEnterEvent e) {
        if (this.active) {
            Player p = e.getPlayer();
            World w = p.getWorld();
            if (w.getName().equalsIgnoreCase("world")) {
                if (w.getEnvironment() == Environment.NORMAL) {
                    if (this.isNight(w)) {
                        e.setCancelled(true);
                        p.sendMessage(this.plugin.lang.tr(p, "event.blood_moon.no_sleep"));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (this.active) {
            LivingEntity var3 = e.getEntity();
            if (var3 instanceof Monster) {
                Monster mob = (Monster)var3;
                if (!this.isCitizensNPC(mob)) {
                    World w = mob.getWorld();
                    if (w.getName().equalsIgnoreCase("world")) {
                        if (w.getEnvironment() == Environment.NORMAL) {
                            if (this.isNight(w)) {
                                mob.addScoreboardTag("aet_blood_moon");
                                this.saveAndBuffAttributes(mob);
                                if (mob.getCustomName() == null) {
                                    this.saveAndApplyName(mob);
                                }

                            }
                        }
                    }
                }
            }
        }
    }

    private void saveAndApplyName(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(this.K_NAME, PersistentDataType.STRING, "");
        pdc.set(this.K_NAME_VISIBLE, PersistentDataType.BYTE, (byte)(le.isCustomNameVisible() ? 1 : 0));
        String mobName = this.plugin.lang.tr((Player)null, "event.blood_moon.mob_name");
        le.setCustomName("§4" + mobName);
        le.setCustomNameVisible(true);
    }

    private void saveAndBuffAttributes(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        AttributeInstance maxHealth = le.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            if (!pdc.has(this.K_MAX_HEALTH, PersistentDataType.DOUBLE)) {
                pdc.set(this.K_MAX_HEALTH, PersistentDataType.DOUBLE, maxHealth.getBaseValue());
            }

            double original = maxHealth.getBaseValue();
            double boosted = original * (double)1.5F;
            double cap = (double)1024.0F;
            double finalValue = Math.min(boosted, cap);
            maxHealth.setBaseValue(finalValue);
            le.setHealth(Math.min(le.getHealth(), finalValue));
            le.setHealth(finalValue);
        }

        AttributeInstance dmg = le.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) {
            if (!pdc.has(this.K_ATTACK_DMG, PersistentDataType.DOUBLE)) {
                pdc.set(this.K_ATTACK_DMG, PersistentDataType.DOUBLE, dmg.getBaseValue());
            }

            dmg.setBaseValue(dmg.getBaseValue() * (double)1.5F);
        }

    }

    private void cleanupWorld(World w) {
        for(Monster m : w.getEntitiesByClass(Monster.class)) {
            if (m.getScoreboardTags().contains("aet_blood_moon")) {
                PersistentDataContainer pdc = m.getPersistentDataContainer();
                if (pdc.has(this.K_NAME, PersistentDataType.STRING)) {
                    String prev = (String)pdc.get(this.K_NAME, PersistentDataType.STRING);
                    byte vis = (Byte)pdc.getOrDefault(this.K_NAME_VISIBLE, PersistentDataType.BYTE, (byte)0);
                    if (prev != null && !prev.isEmpty()) {
                        m.setCustomName(prev);
                    } else {
                        m.setCustomName((String)null);
                    }

                    m.setCustomNameVisible(vis == 1);
                    pdc.remove(this.K_NAME);
                    pdc.remove(this.K_NAME_VISIBLE);
                }

                if (pdc.has(this.K_MAX_HEALTH, PersistentDataType.DOUBLE)) {
                    Double prevMax = (Double)pdc.get(this.K_MAX_HEALTH, PersistentDataType.DOUBLE);
                    AttributeInstance maxHealth = m.getAttribute(Attribute.MAX_HEALTH);
                    if (prevMax != null && maxHealth != null) {
                        maxHealth.setBaseValue(prevMax);
                        m.setHealth(Math.min(m.getHealth(), prevMax));
                    }

                    pdc.remove(this.K_MAX_HEALTH);
                }

                if (pdc.has(this.K_ATTACK_DMG, PersistentDataType.DOUBLE)) {
                    Double prevDmg = (Double)pdc.get(this.K_ATTACK_DMG, PersistentDataType.DOUBLE);
                    AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
                    if (prevDmg != null && dmg != null) {
                        dmg.setBaseValue(prevDmg);
                    }

                    pdc.remove(this.K_ATTACK_DMG);
                }

                m.removeScoreboardTag("aet_blood_moon");
            }
        }

    }

    private boolean isCitizensNPC(Entity entity) {
        return entity != null && entity.hasMetadata("NPC");
    }
}
