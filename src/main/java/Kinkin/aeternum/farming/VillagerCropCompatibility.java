package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.PlatformScheduler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

final class VillagerCropCompatibility implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final CropLoreService lore;
    private final Map<UUID, Villager> tracked = new ConcurrentHashMap();
    private final Queue<Villager> villagers = new ConcurrentLinkedQueue();
    private final Map<UUID, Item> queuedItems = new ConcurrentHashMap();
    private final Queue<Item> items = new ConcurrentLinkedQueue();
    private PlatformScheduler.TaskHandle task;
    private volatile boolean active;
    private volatile boolean warned;
    private static final Method ENTITY_SCHEDULER = schedulerMethod();

    VillagerCropCompatibility(AeternumSeasonsPlugin plugin, CropLoreService lore) {
        super();
        this.plugin = plugin;
        this.lore = lore;
    }

    void register() {
        this.active = true;
        Bukkit.getPluginManager().registerEvents(this, this.plugin);

        for(World world : Bukkit.getWorlds()) {
            for(Entity entity : world.getEntitiesByClasses(new Class[]{Villager.class, Item.class})) {
                this.discover(entity);
            }
        }

        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, 1L, 10L);
    }

    void unregister() {
        this.active = false;
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.tracked.clear();
        this.villagers.clear();
        this.queuedItems.clear();
        this.items.clear();
    }

    private void discover(Entity entity) {
        if (this.active) {
            if (entity instanceof Villager) {
                Villager villager = (Villager)entity;
                if (this.tracked.putIfAbsent(villager.getUniqueId(), villager) == null) {
                    this.villagers.add(villager);
                }
            } else if (entity instanceof Item) {
                Item item = (Item)entity;
                this.queueItem(item);
            }

        }
    }

    void queueItem(Item item) {
        if (this.active && this.queuedItems.putIfAbsent(item.getUniqueId(), item) == null) {
            this.items.add(item);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onSpawn(CreatureSpawnEvent event) {
        this.discover(event.getEntity());
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(this::discover);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for(Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                this.tracked.remove(entity.getUniqueId(), villager);
            }

            if (entity instanceof Item item) {
                this.queuedItems.remove(entity.getUniqueId(), item);
            }
        }

    }

    public void run() {
        if (this.active && this.plugin.isEnabled()) {
            int visits = Math.min(16, this.villagers.size());

            for(int i = 0; i < visits; ++i) {
                Villager villager = (Villager)this.villagers.poll();
                if (villager == null) {
                    break;
                }

                UUID id = villager.getUniqueId();
                if (this.tracked.get(id) == villager) {
                    Runnable retired = () -> this.tracked.remove(id, villager);
                    this.onOwner(villager, () -> {
                        if (this.tracked.get(id) == villager && villager.isValid() && !villager.isDead()) {
                            try {
                                Inventory var10000 = villager.getInventory();
                                CropLoreService var10001 = this.lore;
                                Objects.requireNonNull(var10001);
                                VillagerCropInventory.normalize(var10000, var10001::forVillager);
                            } finally {
                                if (this.active && this.tracked.get(id) == villager) {
                                    this.villagers.add(villager);
                                }

                            }

                        } else {
                            retired.run();
                        }
                    }, retired);
                }
            }

            for(int i = 0; i < 16; ++i) {
                Item item = (Item)this.items.poll();
                if (item == null) {
                    break;
                }

                UUID id = item.getUniqueId();
                if (this.queuedItems.get(id) == item) {
                    Runnable retired = () -> this.queuedItems.remove(id, item);
                    this.onOwner(item, () -> {
                        try {
                            if (item.isValid() && !item.isDead()) {
                                ItemStack current = item.getItemStack();
                                ItemStack canonical = this.lore.forVillager(current);
                                if (canonical != null && !canonical.equals(current)) {
                                    item.setItemStack(canonical);
                                }

                                return;
                            }
                        } finally {
                            retired.run();
                        }

                    }, retired);
                }
            }

        }
    }

    private static Method schedulerMethod() {
        try {
            return Entity.class.getMethod("getScheduler");
        } catch (NoSuchMethodException var1) {
            return null;
        }
    }

    private void onOwner(Entity entity, Runnable action, Runnable retired) {
        if (this.active && this.plugin.isEnabled()) {
            Runnable guarded = () -> {
                if (this.active && this.plugin.isEnabled()) {
                    action.run();
                } else {
                    retired.run();
                }

            };
            if (ENTITY_SCHEDULER == null) {
                Bukkit.getScheduler().runTask(this.plugin, guarded);
            } else {
                try {
                    Object scheduler = ENTITY_SCHEDULER.invoke(entity);
                    Method execute = ENTITY_SCHEDULER.getReturnType().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, Long.TYPE);
                    if (!Boolean.TRUE.equals(execute.invoke(scheduler, this.plugin, guarded, retired, 1L))) {
                        retired.run();
                    }
                } catch (ReflectiveOperationException error) {
                    retired.run();
                    if (!this.warned) {
                        this.warned = true;
                        this.plugin.getLogger().warning("[CropLore] Could not schedule villager compatibility: " + error.getMessage());
                    }
                }

            }
        } else {
            retired.run();
        }
    }
}
