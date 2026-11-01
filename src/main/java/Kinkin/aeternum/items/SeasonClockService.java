package Kinkin.aeternum.items;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.events.SeasonalEvent;
import Kinkin.aeternum.events.SeasonalEventService;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class SeasonClockService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final SeasonalEventService events;
    private final NamespacedKey KEY_CLOCK;
    private final NamespacedKey RECIPE_KEY;
    private ItemStack proto;

    public SeasonClockService(AeternumSeasonsPlugin plugin, SeasonService seasons, SeasonalEventService events) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.events = events;
        this.KEY_CLOCK = new NamespacedKey(plugin, "season_clock");
        this.RECIPE_KEY = new NamespacedKey(plugin, "season_clock");
    }

    public void register() {
        this.buildPrototype();
        this.registerRecipe();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private void buildPrototype() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            this.proto = item;
        } else {
            meta.setDisplayName(this.trServer("items.food.season_clock.name"));
            meta.setLore(List.of(this.trServer("items.food.season_clock.lore_1"), this.trServer("items.food.season_clock.lore_2")));
            int cmd = this.plugin.cfg.survival.getInt("season_clock.custom_model_data", 0);
            if (cmd > 0) {
                meta.setCustomModelData(cmd);
            }

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(this.KEY_CLOCK, PersistentDataType.BYTE, (byte)1);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            item.setItemMeta(meta);
            this.proto = item;
        }
    }

    private void registerRecipe() {
        if (this.proto != null) {
            ShapedRecipe r = new ShapedRecipe(this.RECIPE_KEY, this.proto.clone());
            r.shape(new String[]{" D ", " C ", "   "});
            r.setIngredient('D', Material.COMPASS);
            r.setIngredient('C', Material.CLOCK);
            Bukkit.addRecipe(r);
        }
    }

    private boolean isSeasonClock(ItemStack item) {
        if (item != null && item.getType() == Material.CLOCK) {
            if (!item.hasItemMeta()) {
                return false;
            } else {
                ItemMeta meta = item.getItemMeta();
                if (meta == null) {
                    return false;
                } else {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    Byte v = (Byte)pdc.get(this.KEY_CLOCK, PersistentDataType.BYTE);
                    return v != null && v == 1;
                }
            }
        } else {
            return false;
        }
    }

    private void applyClockLocalization(Player p, ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(this.tr(p, "items.food.season_clock.name"));
                meta.setLore(List.of(this.tr(p, "items.food.season_clock.lore_1"), this.tr(p, "items.food.season_clock.lore_2")));
                item.setItemMeta(meta);
            }
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        HumanEntity var3 = e.getView().getPlayer();
        if (var3 instanceof Player p) {
            CraftingInventory inv = e.getInventory();
            ItemStack result = inv.getResult();
            if (this.isSeasonClock(result)) {
                ItemStack localized = result.clone();
                this.applyClockLocalization(p, localized);
                inv.setResult(localized);
            }
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        HumanEntity var3 = e.getWhoClicked();
        if (var3 instanceof Player p) {
            ItemStack var4 = e.getCurrentItem();
            if (this.isSeasonClock(var4)) {
                this.applyClockLocalization(p, var4);
            }
        }
    }

    @EventHandler(
        ignoreCancelled = false
    )
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            Action a = e.getAction();
            if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = e.getItem();
                if (this.isSeasonClock(item)) {
                    Player p = e.getPlayer();
                    this.sendClockInfo(p);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.6F);
                }
            }
        }
    }

    private void sendClockInfo(Player p) {
        CalendarState st = this.seasons.getStateCopy(p.getWorld());
        p.sendMessage(this.tr(p, "clock.use.header"));
        Map<String, Object> varsDate = new HashMap();
        varsDate.put("day", st.day);
        varsDate.put("max", this.seasons.getDaysPerSeason());
        varsDate.put("season", this.plugin.lang.trOr(p, "season." + st.season.name(), st.season.display()));
        varsDate.put("year", st.year);
        p.sendMessage(this.trf(p, "clock.use.date", varsDate));
        SeasonalEvent active = this.events.getActive();
        if (active != null) {
            Map<String, Object> vars = new HashMap();
            vars.put("event", this.trEventName(p, active));
            vars.put("days", this.events.getDaysRemaining());
            p.sendMessage(this.trf(p, "clock.use.active_event", vars));
        } else {
            p.sendMessage(this.tr(p, "clock.use.no_active_event"));
        }

        if (active != null) {
            p.sendMessage(this.tr(p, "clock.use.next_events.blocked_by_active"));
        } else {
            SeasonalEvent queued = this.events.getQueuedTomorrow();
            if (queued == null) {
                p.sendMessage(this.tr(p, "clock.use.next_events.none"));
            } else {
                Map<String, Object> vars = new HashMap();
                vars.put("event", this.trEventName(p, queued));
                p.sendMessage(this.trf(p, "clock.use.next_events.single", vars));
            }
        }

        World w = p.getWorld();
        boolean storm = w.hasStorm();
        int ticks = w.getWeatherDuration();
        long seconds = (long)ticks / 20L;
        Map<String, Object> varsWeather = new HashMap();
        varsWeather.put("state", storm ? this.tr(p, "clock.weather.raining") : this.tr(p, "clock.weather.clear"));
        varsWeather.put("minutes", Math.max(1L, seconds / 60L));
        p.sendMessage(this.trf(p, "clock.use.weather_line", varsWeather));
    }

    private String trServer(String key) {
        return this.plugin.lang.trServer(key);
    }

    private String tr(Player p, String key) {
        return this.plugin.lang.tr(p, key);
    }

    private String trf(Player p, String key, Map<String, Object> vars) {
        return this.plugin.lang.trf(p, key, vars);
    }

    private String trEventName(Player p, SeasonalEvent ev) {
        String id = ev.getId().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> bases = new LinkedHashSet();
        bases.add("event." + id);
        if (id.equals("season_festival")) {
            bases.add("event.festival");
        }

        if (id.endsWith("_festival")) {
            String var10001 = id.substring(0, id.length() - "_festival".length());
            bases.add("event." + var10001);
        }

        if (id.endsWith("_blessing")) {
            String var9 = id.substring(0, id.length() - "_blessing".length());
            bases.add("event." + var9);
        }

        for(String base : bases) {
            String v = this.plugin.lang.tr(p, base + ".name");
            if (v != null && !v.equals(base + ".name")) {
                return v;
            }

            v = this.plugin.lang.tr(p, base + ".title");
            if (v != null && !v.equals(base + ".title")) {
                return v;
            }
        }

        return ev.getDisplayName();
    }
}
