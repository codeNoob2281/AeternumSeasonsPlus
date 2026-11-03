package kinkin.aeternum.items;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.events.SeasonalEvent;
import kinkin.aeternum.events.SeasonalEventService;
import org.bukkit.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * 季节时钟物品服务。
 *
 * <p>注册一个可合成的“季节时钟”物品（以普通时钟为基础，通过持久化数据打标记），并负责：
 * <ul>
 *     <li>构建物品原型并注册合成配方（指南针 + 时钟）；</li>
 *     <li>在合成预览与成品中按玩家语言本地化名称与 Lore；</li>
 *     <li>右键使用时向玩家发送当前日期、活动事件与天气信息（播放提示音）。</li>
 * </ul>
 *
 * <p>依赖：{@link SeasonService} 提供日历状态，{@link SeasonalEventService} 提供活动事件信息，
 * 语言通过 {@code plugin.lang} 获取。</p>
 */
public final class SeasonClockService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final SeasonalEventService events;
    /**
     * 标记物品为季节时钟的持久化键。
     */
    private final NamespacedKey KEY_CLOCK;
    /**
     * 合成配方使用的命名空间键。
     */
    private final NamespacedKey RECIPE_KEY;
    /**
     * 季节时钟物品原型。
     */
    private ItemStack proto;

    public SeasonClockService(AeternumSeasonsPlugin plugin, SeasonService seasons, SeasonalEventService events) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.events = events;
        this.KEY_CLOCK = new NamespacedKey(plugin, "season_clock");
        this.RECIPE_KEY = new NamespacedKey(plugin, "season_clock");
    }

    /**
     * 构建物品原型、注册配方并注册事件监听。
     */
    public void register() {
        this.buildPrototype();
        this.registerRecipe();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 注销事件监听。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 构建季节时钟物品原型：设置名称、Lore、自定义模型数据与持久化标记。
     */
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
            pdc.set(this.KEY_CLOCK, PersistentDataType.BYTE, (byte) 1);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            item.setItemMeta(meta);
            this.proto = item;
        }
    }

    /**
     * 注册季节时钟的合成配方（上中放指南针，中间放时钟）。
     */
    private void registerRecipe() {
        if (this.proto != null) {
            ShapedRecipe recipe = new ShapedRecipe(this.RECIPE_KEY, this.proto.clone());
            recipe.shape(new String[]{" D ", " C ", "   "});
            recipe.setIngredient('D', Material.COMPASS);
            recipe.setIngredient('C', Material.CLOCK);
            Bukkit.addRecipe(recipe);
        }
    }

    /**
     * 判断物品是否为季节时钟（时钟材质 + 持久化标记为 1）。
     */
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
                    Byte marker = (Byte) pdc.get(this.KEY_CLOCK, PersistentDataType.BYTE);
                    return marker != null && marker == 1;
                }
            }
        } else {
            return false;
        }
    }

    /**
     * 按玩家语言本地化季节时钟的名称与 Lore。
     */
    private void applyClockLocalization(Player player, ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(this.tr(player, "items.food.season_clock.name"));
                meta.setLore(List.of(this.tr(player, "items.food.season_clock.lore_1"), this.tr(player, "items.food.season_clock.lore_2")));
                item.setItemMeta(meta);
            }
        }
    }

    /**
     * 合成预览：若结果为季节时钟，则替换为按合成者语言本地化的副本。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        HumanEntity viewer = e.getView().getPlayer();
        if (viewer instanceof Player player) {
            CraftingInventory inventory = e.getInventory();
            ItemStack result = inventory.getResult();
            if (this.isSeasonClock(result)) {
                ItemStack localized = result.clone();
                this.applyClockLocalization(player, localized);
                inventory.setResult(localized);
            }
        }
    }

    /**
     * 合成完成：对合成出的季节时钟按玩家语言本地化。
     */
    @EventHandler
    public void onCraft(CraftItemEvent e) {
        HumanEntity clicker = e.getWhoClicked();
        if (clicker instanceof Player player) {
            ItemStack currentItem = e.getCurrentItem();
            if (this.isSeasonClock(currentItem)) {
                this.applyClockLocalization(player, currentItem);
            }
        }
    }

    /**
     * 右键使用季节时钟：在主手右键空气/方块时发送信息并播放提示音。
     */
    @EventHandler(
            ignoreCancelled = false
    )
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            Action action = e.getAction();
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = e.getItem();
                if (this.isSeasonClock(item)) {
                    Player player = e.getPlayer();
                    this.sendClockInfo(player);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.6F);
                }
            }
        }
    }

    /**
     * 向玩家发送季节时钟信息：日期、进行中的活动事件、次日预告与当前天气。
     */
    private void sendClockInfo(Player player) {
        CalendarState state = this.seasons.getStateCopy(player.getWorld());
        player.sendMessage(this.tr(player, "clock.use.header"));
        Map<String, Object> varsDate = new HashMap();
        varsDate.put("day", state.day);
        varsDate.put("max", this.seasons.getDaysPerSeason());
        varsDate.put("season", this.plugin.lang.trOr(player, "season." + state.season.name(), state.season.display()));
        varsDate.put("year", state.year);
        player.sendMessage(this.trf(player, "clock.use.date", varsDate));
        SeasonalEvent active = this.events.getActive();
        if (active != null) {
            Map<String, Object> eventVars = new HashMap();
            eventVars.put("event", this.trEventName(player, active));
            eventVars.put("days", this.events.getDaysRemaining());
            player.sendMessage(this.trf(player, "clock.use.active_event", eventVars));
        } else {
            player.sendMessage(this.tr(player, "clock.use.no_active_event"));
        }

        if (active != null) {
            player.sendMessage(this.tr(player, "clock.use.next_events.blocked_by_active"));
        } else {
            SeasonalEvent queued = this.events.getQueuedTomorrow();
            if (queued == null) {
                player.sendMessage(this.tr(player, "clock.use.next_events.none"));
            } else {
                Map<String, Object> eventVars = new HashMap();
                eventVars.put("event", this.trEventName(player, queued));
                player.sendMessage(this.trf(player, "clock.use.next_events.single", eventVars));
            }
        }

        World world = player.getWorld();
        boolean storm = world.hasStorm();
        int ticks = world.getWeatherDuration();
        long seconds = (long) ticks / 20L;
        Map<String, Object> varsWeather = new HashMap();
        varsWeather.put("state", storm ? this.tr(player, "clock.weather.raining") : this.tr(player, "clock.weather.clear"));
        varsWeather.put("minutes", Math.max(1L, seconds / 60L));
        player.sendMessage(this.trf(player, "clock.use.weather_line", varsWeather));
    }

    /**
     * 服务端默认语言翻译的便捷包装。
     */
    private String trServer(String key) {
        return this.plugin.lang.trServer(key);
    }

    /**
     * 按玩家语言翻译的便捷包装。
     */
    private String tr(Player player, String key) {
        return this.plugin.lang.tr(player, key);
    }

    /**
     * 按玩家语言翻译并替换占位符的便捷包装。
     */
    private String trf(Player player, String key, Map<String, Object> vars) {
        return this.plugin.lang.trf(player, key, vars);
    }

    /**
     * 解析活动事件的本地化名称，兼容 {@code _festival}/{@code _blessing} 等旧命名。
     */
    private String trEventName(Player player, SeasonalEvent event) {
        String id = event.getId().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> bases = new LinkedHashSet();
        bases.add("event." + id);
        if ("season_festival".equals(id)) {
            bases.add("event.festival");
        }

        if (id.endsWith("_festival")) {
            String festivalBase = id.substring(0, id.length() - "_festival".length());
            bases.add("event." + festivalBase);
        }

        if (id.endsWith("_blessing")) {
            String blessingBase = id.substring(0, id.length() - "_blessing".length());
            bases.add("event." + blessingBase);
        }

        for (String base : bases) {
            String value = this.plugin.lang.tr(player, base + ".name");
            if (value != null && !value.equals(base + ".name")) {
                return value;
            }

            value = this.plugin.lang.tr(player, base + ".title");
            if (value != null && !value.equals(base + ".title")) {
                return value;
            }
        }

        return event.getDisplayName();
    }
}
