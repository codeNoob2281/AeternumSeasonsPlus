package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.world.WinterWorldGuardHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MagicStormEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private boolean globalParticlesEnabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private List<Season> allowedSeasons;
    private boolean active = false;
    private double rareMobChance;
    private double extraLootChance;
    private final NamespacedKey kStormMark;
    private final NamespacedKey kPrevName;
    private final NamespacedKey kPrevNameVisible;
    private int tickCounter = 0;
    private static final Enchantment[] WEAPON_ENCHANTS;
    private static final Enchantment[] ARMOR_ENCHANTS;
    private static final Enchantment[] BOW_ENCHANTS;
    private static final Enchantment[] FISHING_ENCHANTS;
    private static final Enchantment[] TOOL_ENCHANTS;
    private static final Enchantment[] ANY_ENCHANTS;

    public MagicStormEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.kStormMark = new NamespacedKey(plugin, "magicstorm_mark");
        this.kPrevName = new NamespacedKey(plugin, "magicstorm_prev_name");
        this.kPrevNameVisible = new NamespacedKey(plugin, "magicstorm_prev_name_visible");
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.magic_storm.enabled", true);
        this.globalParticlesEnabled = y.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = y.getInt("events.magic_storm.min_duration_days", 1);
        this.maxDur = y.getInt("events.magic_storm.max_duration_days", 1);
        this.baseChance = y.getDouble("events.magic_storm.base_chance_per_day", 0.08);
        this.rareMobChance = y.getDouble("events.magic_storm.rare_mob_chance", 0.35);
        this.extraLootChance = y.getDouble("events.magic_storm.extra_loot_chance", 0.4);
        this.allowedSeasons = new ArrayList();

        for(String s : y.getStringList("events.magic_storm.allowed_seasons")) {
            try {
                this.allowedSeasons.add(Season.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException var5) {
            }
        }

        if (this.allowedSeasons.isEmpty()) {
            this.allowedSeasons.addAll(Arrays.asList(Season.values()));
        }

    }

    public String getId() {
        return "magic_storm";
    }

    public String getDisplayName() {
        return "Magic Storm";
    }

    public boolean isSeasonAllowed(Season season) {
        return this.allowedSeasons.contains(season);
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
        } else if (!this.allowedSeasons.contains(st.season)) {
            return false;
        } else {
            return Math.random() < this.baseChance;
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        this.active = true;

        for(World w : ctx.overworlds()) {
            w.setStorm(true);
            w.setThundering(true);
            w.setWeatherDuration(24000);
            w.playSound(w.getSpawnLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6F, 0.4F);
        }

        for(Player p : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(p, "event.magic_storm.title");
            String sub = this.plugin.lang.tr(p, "event.magic_storm.subtitle");
            p.sendTitle(title, sub, 20, 80, 40);
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.active = false;

        for(World w : ctx.overworlds()) {
            if (w.getEnvironment() == Environment.NORMAL) {
                w.setThundering(false);
                w.setStorm(false);

                for(Monster mob : w.getEntitiesByClass(Monster.class)) {
                    PersistentDataContainer pdc = mob.getPersistentDataContainer();
                    if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                        String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
                        Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
                        mob.setGlowing(false);
                        mob.removePotionEffect(PotionEffectType.SPEED);
                        mob.removePotionEffect(PotionEffectType.STRENGTH);
                        if (prevName != null && !prevName.isEmpty()) {
                            mob.setCustomName(prevName);
                        } else {
                            mob.setCustomName((String)null);
                        }

                        mob.setCustomNameVisible(prevVis != null && prevVis == 1);
                        pdc.remove(this.kStormMark);
                        pdc.remove(this.kPrevName);
                        pdc.remove(this.kPrevNameVisible);
                    }
                }
            }
        }

        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.magic_storm.end"));
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        if (this.active) {
            for(World w : ctx.overworlds()) {
                if (w.getEnvironment() == Environment.NORMAL && (!w.hasStorm() || !w.isThundering())) {
                    w.setStorm(true);
                    w.setThundering(true);
                }
            }

            for(Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().getEnvironment() == Environment.NORMAL && !p.isDead() && this.globalParticlesEnabled) {
                    p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add((double)0.0F, (double)1.5F, (double)0.0F), 2, 0.3, 0.4, 0.3, 0.01);
                }
            }

            ++this.tickCounter;
            if (this.tickCounter >= 40) {
                this.tickCounter = 0;

                for(Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().getEnvironment() == Environment.NORMAL) {
                        World w = p.getWorld();

                        for(Entity e : w.getNearbyEntities(p.getLocation(), (double)24.0F, (double)16.0F, (double)24.0F)) {
                            if (e instanceof Monster) {
                                Monster mob = (Monster)e;
                                this.tryMakeRareMob(mob, p);
                            }
                        }
                    }
                }
            }

        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Monster mob) {
            World w = e.getLocation().getWorld();
            if (w != null) {
                if (w.getEnvironment() == Environment.NORMAL) {
                    if (this.active) {
                        Player closest = null;
                        double best = Double.MAX_VALUE;

                        for(Player p : w.getPlayers()) {
                            double d2 = p.getLocation().distanceSquared(mob.getLocation());
                            if (d2 < best) {
                                best = d2;
                                closest = p;
                            }
                        }

                        this.tryMakeRareMob(mob, closest);
                    }
                }
            }
        }
    }

    private void tryMakeRareMob(Monster mob, @Nullable Player viewer) {
        if (!mob.isDead()) {
            World w = mob.getWorld();
            if (w.getEnvironment() == Environment.NORMAL) {
                if (this.active) {
                    String existingName = mob.getCustomName();
                    if (existingName == null || existingName.isBlank()) {
                        PersistentDataContainer pdc = mob.getPersistentDataContainer();
                        if (!pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                            if (!(Math.random() > this.rareMobChance)) {
                                String stormName = viewer != null ? this.plugin.lang.tr(viewer, "event.magic_storm.mob_name") : this.plugin.lang.trServer("event.magic_storm.mob_name");
                                pdc.set(this.kPrevName, PersistentDataType.STRING, existingName == null ? "" : existingName);
                                pdc.set(this.kPrevNameVisible, PersistentDataType.BYTE, (byte)(mob.isCustomNameVisible() ? 1 : 0));
                                pdc.set(this.kStormMark, PersistentDataType.BYTE, (byte)1);
                                mob.setGlowing(true);
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6000, 1, true, false, true));
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 6000, 0, true, false, true));
                                mob.setCustomName(stormName);
                                mob.setCustomNameVisible(true);
                                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                                    if (!mob.isDead()) {
                                        mob.setGlowing(false);
                                    }

                                }, 100L);
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Monster) {
            World w = e.getEntity().getWorld();
            if (w.getEnvironment() == Environment.NORMAL) {
                if (this.active) {
                    if (!(Math.random() > this.extraLootChance)) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        ItemStack reward;
                        if (rnd.nextDouble() < (double)0.5F) {
                            reward = this.createRandomEnchantedBook(rnd);
                        } else {
                            reward = this.createRandomEnchantedGear(rnd);
                        }

                        e.getDrops().add(reward);
                    }
                }
            }
        }
    }

    private ItemStack createRandomEnchantedBook(ThreadLocalRandom rnd) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
        if (meta != null && ANY_ENCHANTS.length != 0) {
            Enchantment ench = this.randomFrom(ANY_ENCHANTS, rnd);
            int level = this.randomLevelFor(ench, false, rnd);
            meta.addStoredEnchant(ench, level, true);
            book.setItemMeta(meta);
            return book;
        } else {
            return book;
        }
    }

    private ItemStack createRandomEnchantedGear(ThreadLocalRandom rnd) {
        double r = rnd.nextDouble();
        ItemStack item;
        if (r < 0.4) {
            Material type = rnd.nextBoolean() ? Material.IRON_SWORD : Material.IRON_AXE;
            item = new ItemStack(type);
            Enchantment ench = this.randomFrom(WEAPON_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (r < 0.7) {
            Material[] armorTypes = new Material[]{Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
            item = new ItemStack(armorTypes[rnd.nextInt(armorTypes.length)]);
            Enchantment ench = this.randomFrom(ARMOR_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (r < 0.85) {
            item = new ItemStack(Material.BOW);
            Enchantment ench = this.randomFrom(BOW_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (r < 0.95) {
            item = new ItemStack(Material.FISHING_ROD);
            Enchantment ench = this.randomFrom(FISHING_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else {
            Material[] tools = new Material[]{Material.IRON_PICKAXE, Material.IRON_AXE, Material.IRON_SHOVEL};
            item = new ItemStack(tools[rnd.nextInt(tools.length)]);
            Enchantment ench = this.randomFrom(TOOL_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        }

        return item;
    }

    private void applyEnchant(ItemStack item, Enchantment ench, ThreadLocalRandom rnd) {
        int level = this.randomLevelFor(ench, true, rnd);
        item.addUnsafeEnchantment(ench, level);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable dmg) {
            dmg.setDamage(0);
            item.setItemMeta(meta);
        }

    }

    private Enchantment randomFrom(Enchantment[] pool, ThreadLocalRandom rnd) {
        return pool[rnd.nextInt(pool.length)];
    }

    private int randomLevelFor(Enchantment ench, boolean gear, ThreadLocalRandom rnd) {
        int max = ench.getMaxLevel();
        if (gear) {
            max = Math.min(max, 3);
        } else {
            max = Math.min(max, 4);
        }

        int min = 1;
        return max <= min ? max : rnd.nextInt(min, max + 1);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (w.getEnvironment() == Environment.NORMAL) {
            if (!this.active) {
                for(Entity ent : e.getChunk().getEntities()) {
                    if (ent instanceof Monster) {
                        Monster mob = (Monster)ent;
                        PersistentDataContainer pdc = mob.getPersistentDataContainer();
                        if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                            String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
                            Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
                            mob.setGlowing(false);
                            mob.removePotionEffect(PotionEffectType.SPEED);
                            mob.removePotionEffect(PotionEffectType.STRENGTH);
                            if (prevName != null && !prevName.isEmpty()) {
                                mob.setCustomName(prevName);
                            } else {
                                mob.setCustomName((String)null);
                            }

                            mob.setCustomNameVisible(prevVis != null && prevVis == 1);
                            pdc.remove(this.kStormMark);
                            pdc.remove(this.kPrevName);
                            pdc.remove(this.kPrevNameVisible);
                        }
                    }
                }

            }
        }
    }

    private void cleanupStormMob(Monster mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
            String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
            Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
            mob.setGlowing(false);
            mob.removePotionEffect(PotionEffectType.SPEED);
            mob.removePotionEffect(PotionEffectType.STRENGTH);
            if (prevName != null && !prevName.isEmpty()) {
                mob.setCustomName(prevName);
            } else {
                mob.setCustomName((String)null);
            }

            mob.setCustomNameVisible(prevVis != null && prevVis == 1);
            pdc.remove(this.kStormMark);
            pdc.remove(this.kPrevName);
            pdc.remove(this.kPrevNameVisible);
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onLightningStrike(LightningStrikeEvent e) {
        if (this.active) {
            Location loc = e.getLightning().getLocation();
            World w = loc.getWorld();
            if (w != null && w.getEnvironment() == Environment.NORMAL) {
                Block b = w.getBlockAt(loc);
                if (!WinterWorldGuardHelper.canModify(b)) {
                    e.setCancelled(true);
                }

            }
        }
    }

    static {
        WEAPON_ENCHANTS = new Enchantment[]{Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS, Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE, Enchantment.KNOCKBACK, Enchantment.UNBREAKING, Enchantment.MENDING};
        ARMOR_ENCHANTS = new Enchantment[]{Enchantment.PROTECTION, Enchantment.PROJECTILE_PROTECTION, Enchantment.FIRE_PROTECTION, Enchantment.BLAST_PROTECTION, Enchantment.THORNS, Enchantment.UNBREAKING, Enchantment.MENDING};
        BOW_ENCHANTS = new Enchantment[]{Enchantment.POWER, Enchantment.PUNCH, Enchantment.FLAME, Enchantment.INFINITY, Enchantment.UNBREAKING, Enchantment.MENDING};
        FISHING_ENCHANTS = new Enchantment[]{Enchantment.LUCK_OF_THE_SEA, Enchantment.LURE, Enchantment.UNBREAKING, Enchantment.MENDING};
        TOOL_ENCHANTS = new Enchantment[]{Enchantment.EFFICIENCY, Enchantment.FORTUNE, Enchantment.SILK_TOUCH, Enchantment.UNBREAKING, Enchantment.MENDING};
        List<Enchantment> all = new ArrayList();
        all.addAll(Arrays.asList(WEAPON_ENCHANTS));
        all.addAll(Arrays.asList(ARMOR_ENCHANTS));
        all.addAll(Arrays.asList(BOW_ENCHANTS));
        all.addAll(Arrays.asList(FISHING_ENCHANTS));
        all.addAll(Arrays.asList(TOOL_ENCHANTS));
        Set<Enchantment> set = new LinkedHashSet(all);
        ANY_ENCHANTS = (Enchantment[])set.toArray(new Enchantment[0]);
    }
}
