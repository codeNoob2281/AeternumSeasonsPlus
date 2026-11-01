package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class FrostBossManager implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private static final double DAILY_BOSS_CHANCE = 0.37;
    private final Random random = new Random();
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasonService;
    private final NamespacedKey BOSS_KEY;
    private final NamespacedKey EVERFROST_SHARD_KEY;
    private final NamespacedKey GLACIALIS_CORE_KEY;
    private final NamespacedKey HEAT_ARMOR_KEY;
    private BukkitTask calendarTask;
    private UUID currentBossId;

    public FrostBossManager(AeternumSeasonsPlugin plugin, SeasonService seasonService) {
        super();
        this.plugin = plugin;
        this.seasonService = seasonService;
        this.BOSS_KEY = new NamespacedKey(plugin, "glacialis_boss");
        this.EVERFROST_SHARD_KEY = new NamespacedKey(plugin, "everfrost_shard");
        this.GLACIALIS_CORE_KEY = new NamespacedKey(plugin, "glacialis_core");
        this.HEAT_ARMOR_KEY = new NamespacedKey(plugin, "heat_armor");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.startCalendarTask();
        this.registerRecipes();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        if (this.calendarTask != null) {
            this.calendarTask.cancel();
        }

    }

    private void startCalendarTask() {
        this.calendarTask = (new BukkitRunnable() {
            private int lastDay = -1;
            private int lastYear = -1;

            public void run() {
                World frost = Bukkit.getWorld("aeternum_frost");
                if (frost != null) {
                    CalendarState st = FrostBossManager.this.seasonService.getStateCopy(frost);
                    if (st != null) {
                        if (st.day != this.lastDay || st.year != this.lastYear) {
                            this.lastDay = st.day;
                            this.lastYear = st.year;
                            FrostBossManager.this.onNewDay(st);
                        }

                    }
                }
            }
        }).runTaskTimer(this.plugin, 200L, 200L);
    }

    private void onNewDay(CalendarState state) {
        if (this.currentBossId != null) {
            Entity existing = this.findCurrentBoss();
            if (existing != null && !existing.isDead()) {
                return;
            }

            this.currentBossId = null;
        }

        World frost = Bukkit.getWorld("aeternum_frost");
        if (frost != null) {
            List<Player> candidates = frost.getPlayers();
            if (!candidates.isEmpty()) {
                if (!(this.random.nextDouble() >= 0.37)) {
                    Player target = (Player)candidates.get(this.random.nextInt(candidates.size()));
                    this.spawnGlacialisNear(target);
                }
            }
        }
    }

    private void spawnGlacialisNear(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        Location spawn = base.clone();
        int y = w.getHighestBlockYAt(base) + 15;
        y = Math.min(y, w.getMaxHeight() - 5);
        spawn.setY((double)y);
        Wither boss = (Wither)w.spawn(spawn, Wither.class, (wtr) -> {
            this.markAsBoss(wtr);
            this.buffBossStats(wtr);
            String name = this.plugin.lang.tr(p, "boss.glacialis.name");
            wtr.setCustomName(name);
            wtr.setCustomNameVisible(true);
            wtr.setRemoveWhenFarAway(false);
        });
        this.currentBossId = boss.getUniqueId();
        String msg = this.plugin.lang.tr(p, "boss.glacialis.spawn");

        for(Player pl : w.getPlayers()) {
            pl.sendMessage(msg);
            pl.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0F, 0.5F);
        }

    }

    private void markAsBoss(Entity e) {
        e.getPersistentDataContainer().set(this.BOSS_KEY, PersistentDataType.BYTE, (byte)1);
    }

    private boolean isBoss(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        Byte b = (Byte)pdc.get(this.BOSS_KEY, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    private Entity findCurrentBoss() {
        return this.currentBossId == null ? null : Bukkit.getEntity(this.currentBossId);
    }

    private void buffBossStats(LivingEntity ent) {
        AttributeInstance maxHealth = ent.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue((double)600.0F);
            ent.setHealth(maxHealth.getBaseValue());
        }

        AttributeInstance attack = ent.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue((double)20.0F);
        }

        AttributeInstance movement = ent.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * 1.2);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossExplode(EntityExplodeEvent e) {
        Entity source = e.getEntity();
        Entity owner = null;
        if (source instanceof Wither wither) {
            owner = wither;
        } else if (source instanceof WitherSkull skull) {
            ProjectileSource var7 = skull.getShooter();
            if (var7 instanceof Entity shooter) {
                owner = shooter;
            }
        }

        if (owner instanceof Wither wither) {
            if (this.isBoss(wither)) {
                e.blockList().clear();
                World w = source.getWorld();
                Location center = source.getLocation();
                int radius = 4;

                for(int dx = -radius; dx <= radius; ++dx) {
                    for(int dz = -radius; dz <= radius; ++dz) {
                        double distSq = (double)(dx * dx + dz * dz);
                        if (!(distSq > (double)(radius * radius))) {
                            int x = center.getBlockX() + dx;
                            int z = center.getBlockZ() + dz;
                            int y = center.getBlockY();
                            Block ground = w.getBlockAt(x, y - 1, z);
                            Block target = w.getBlockAt(x, y, z);
                            if (target.getType().isAir() && ground.getType().isSolid()) {
                                target.setType(Material.POWDER_SNOW, false);
                            }
                        }
                    }
                }

                for(Entity ent : w.getNearbyEntities(center, (double)5.0F, (double)5.0F, (double)5.0F)) {
                    if (ent instanceof Player) {
                        Player pl = (Player)ent;
                        pl.setFreezeTicks(Math.min(pl.getFreezeTicks() + 100, 200));
                    }
                }

                w.spawnParticle(Particle.SNOWFLAKE, center, 120, (double)2.0F, (double)1.0F, (double)2.0F, 0.1);
                w.playSound(center, Sound.BLOCK_POWDER_SNOW_FALL, 1.5F, 0.7F);
                return;
            }
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossChangeBlock(EntityChangeBlockEvent e) {
        Entity var3 = e.getEntity();
        if (var3 instanceof Wither wither) {
            if (this.isBoss(wither)) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossDeath(EntityDeathEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Wither wither) {
            if (this.isBoss(wither)) {
                this.currentBossId = null;
                e.getDrops().clear();
                World w = wither.getWorld();
                Location loc = wither.getLocation();
                Player killer = wither.getKiller();
                w.dropItemNaturally(loc, this.createGlacialisCore(killer));
                int shards = 4 + (new Random()).nextInt(3);

                for(int i = 0; i < shards; ++i) {
                    w.dropItemNaturally(loc, this.createEverfrostShard(killer));
                }

                String msg = this.plugin.lang.tr((Player)null, "boss.glacialis.defeated");

                for(Player p : w.getPlayers()) {
                    p.sendMessage(msg);
                    p.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
                }

                e.setDroppedExp(300);
            }
        }
    }

    public ItemStack createEverfrostShard(Player viewer) {
        ItemStack it = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, "item.everfrost_shard.name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, "item.everfrost_shard.lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, "item.everfrost_shard.lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.EVERFROST_SHARD_KEY, PersistentDataType.BYTE, (byte)1);
            it.setItemMeta(meta);
        }

        return it;
    }

    public ItemStack createGlacialisCore(Player viewer) {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, "item.glacialis_core.name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, "item.glacialis_core.lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, "item.glacialis_core.lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.GLACIALIS_CORE_KEY, PersistentDataType.BYTE, (byte)1);
            it.setItemMeta(meta);
        }

        return it;
    }

    private ItemStack createHeatArmorPiece(Player viewer, Material mat, String nameKeyPrefix) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, nameKeyPrefix + ".name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, nameKeyPrefix + ".lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, nameKeyPrefix + ".lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.HEAT_ARMOR_KEY, PersistentDataType.BYTE, (byte)1);
            it.setItemMeta(meta);
        }

        return it;
    }

    private void registerRecipes() {
        ShapedRecipe rHelmet = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_helmet"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_HELMET, "item.heat_armor.helmet"));
        rHelmet.shape(new String[]{"SSS", "SCS", " H "});
        rHelmet.setIngredient('S', Material.PRISMARINE_SHARD);
        rHelmet.setIngredient('C', Material.HEART_OF_THE_SEA);
        rHelmet.setIngredient('H', Material.DIAMOND_HELMET);
        Bukkit.addRecipe(rHelmet);
        ShapedRecipe rChest = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_chest"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_CHESTPLATE, "item.heat_armor.chest"));
        rChest.shape(new String[]{" C ", "SAS", "S S"});
        rChest.setIngredient('S', Material.PRISMARINE_SHARD);
        rChest.setIngredient('C', Material.HEART_OF_THE_SEA);
        rChest.setIngredient('A', Material.DIAMOND_CHESTPLATE);
        Bukkit.addRecipe(rChest);
        ShapedRecipe rLegs = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_legs"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_LEGGINGS, "item.heat_armor.legs"));
        rLegs.shape(new String[]{"SCS", "SAS", "SAS"});
        rLegs.setIngredient('S', Material.PRISMARINE_SHARD);
        rLegs.setIngredient('C', Material.HEART_OF_THE_SEA);
        rLegs.setIngredient('A', Material.DIAMOND_LEGGINGS);
        Bukkit.addRecipe(rLegs);
        ShapedRecipe rBoots = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_boots"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_BOOTS, "item.heat_armor.boots"));
        rBoots.shape(new String[]{" C ", "SAS", "S S"});
        rBoots.setIngredient('S', Material.PRISMARINE_SHARD);
        rBoots.setIngredient('C', Material.HEART_OF_THE_SEA);
        rBoots.setIngredient('A', Material.DIAMOND_BOOTS);
        Bukkit.addRecipe(rBoots);
    }

    public boolean isHeatArmorPiece(ItemStack it) {
        if (it == null) {
            return false;
        } else if (!it.hasItemMeta()) {
            return false;
        } else {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte b = (Byte)meta.getPersistentDataContainer().get(this.HEAT_ARMOR_KEY, PersistentDataType.BYTE);
                return b != null && b == 1;
            }
        }
    }

    private boolean hasFlag(ItemStack it, NamespacedKey key) {
        if (it != null && it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte b = (Byte)meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
                return b != null && b == 1;
            }
        } else {
            return false;
        }
    }

    private boolean isEverfrostRecipe(Recipe r) {
        if (!(r instanceof ShapedRecipe sr)) {
            return false;
        } else {
            NamespacedKey k = sr.getKey();
            return k != null && k.getKey() != null && k.getKey().startsWith("everfrost_");
        }
    }

    private Player firstViewer(PrepareItemCraftEvent e) {
        for(HumanEntity he : e.getViewers()) {
            if (he instanceof Player p) {
                return p;
            }
        }

        return null;
    }

    private boolean matrixHasValidPdc(ItemStack[] matrix) {
        if (matrix == null) {
            return false;
        } else {
            for(ItemStack it : matrix) {
                if (it != null && it.getType() != Material.AIR) {
                    if (it.getType() == Material.PRISMARINE_SHARD) {
                        if (!this.hasFlag(it, this.EVERFROST_SHARD_KEY)) {
                            return false;
                        }
                    } else if (it.getType() == Material.HEART_OF_THE_SEA && !this.hasFlag(it, this.GLACIALIS_CORE_KEY)) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    private ItemStack localizedResultFor(Recipe r, Player viewer) {
        if (r instanceof ShapedRecipe) {
            ShapedRecipe sr = (ShapedRecipe)r;
            ItemStack var10000;
            switch (sr.getKey().getKey()) {
                case "everfrost_helmet" -> var10000 = this.createHeatArmorPiece(viewer, Material.DIAMOND_HELMET, "item.heat_armor.helmet");
                case "everfrost_chest" -> var10000 = this.createHeatArmorPiece(viewer, Material.DIAMOND_CHESTPLATE, "item.heat_armor.chest");
                case "everfrost_legs" -> var10000 = this.createHeatArmorPiece(viewer, Material.DIAMOND_LEGGINGS, "item.heat_armor.legs");
                case "everfrost_boots" -> var10000 = this.createHeatArmorPiece(viewer, Material.DIAMOND_BOOTS, "item.heat_armor.boots");
                default -> var10000 = null;
            }

            return var10000;
        } else {
            return null;
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPrepareEverfrostCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (this.isEverfrostRecipe(r)) {
            CraftingInventory inv = e.getInventory();
            ItemStack[] matrix = inv.getMatrix();
            if (!this.matrixHasValidPdc(matrix)) {
                inv.setResult((ItemStack)null);
            } else {
                Player viewer = this.firstViewer(e);
                inv.setResult(this.localizedResultFor(r, viewer));
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onCraftEverfrost(CraftItemEvent e) {
        Recipe r = e.getRecipe();
        if (this.isEverfrostRecipe(r)) {
            CraftingInventory inv = e.getInventory();
            if (inv instanceof CraftingInventory) {
                if (!this.matrixHasValidPdc(inv.getMatrix())) {
                    e.setCancelled(true);
                    inv.setResult((ItemStack)null);
                }

            }
        }
    }
}
