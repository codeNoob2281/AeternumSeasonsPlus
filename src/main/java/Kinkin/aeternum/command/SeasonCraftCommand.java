package kinkin.aeternum.command;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.crafting.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * /seasoncraft 命令与物品开关 GUI。
 *
 * <player>面向拥有 {@code aeternum.seasoncraft} 权限的玩家，打开一个“合成/物品开关”容器界面，
 * 列出各个季节合成物（工具、靴子、灯笼、火把、铃铛、堆肥桶、傀儡核心等），点击图标即可
 * 启用/禁用对应配方或物品逻辑。各条目的启用状态持久化在 config.yml 的
 * {@code seasoncraft.recipes.<id>}。</player>
 *
 * <player>构造时会把所有条目登记到 {@link #entries}，并确保配置默认值与启用状态同步。</player>
 */
public final class SeasonCraftCommand implements CommandExecutor, Listener {
    private static final String PERM = "aeternum.seasoncraft";
    private static final String CFG_ROOT = "seasoncraft";
    private static final String CFG_RECIPES = "seasoncraft.recipes";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey pdcIdKey;
    private final List<Entry> entries = new ArrayList();

    /**
     * 构造命令并登记所有可开关条目。
     *
     * <player>每条条目包含 id、标题、图标材质，以及用于启用/禁用的 Runnable（通常重新注册/注销
     * 对应的配方或监听器）。</player>
     */
    public SeasonCraftCommand(AeternumSeasonsPlugin plugin, HarvestHoe harvestHoe, NaturalTreeAxe naturalTreeAxe, SnowStepBoots snowStepBoots, SpringStrideBoots springStrideBoots, LabradorBoots labradorBoots, SolarTorch solarTorch, LunarLantern lunarLantern, SpringBell springBell, AdvancedComposter advancedComposter, GolemUpgrades golemUpgrades) {
        super();
        this.plugin = plugin;
        this.pdcIdKey = new NamespacedKey(plugin, "seasoncraft_entry_id");
        List<Entry> entryList = this.entries;
        Material icon = Material.DIAMOND_HOE;
        Runnable enableAction = () -> {
            harvestHoe.unregister();
            harvestHoe.register();
        };
        Objects.requireNonNull(harvestHoe);
        entryList.add(new Entry("harvest_hoe", "Harvest Hoe", icon, enableAction, harvestHoe::unregister));
        entryList = this.entries;
        icon = Material.DIAMOND_AXE;
        enableAction = () -> {
            naturalTreeAxe.unregister();
            naturalTreeAxe.register();
        };
        Objects.requireNonNull(naturalTreeAxe);
        entryList.add(new Entry("natural_tree_axe", "Natural Tree Axe", icon, enableAction, naturalTreeAxe::unregister));
        entryList = this.entries;
        icon = Material.LEATHER_BOOTS;
        enableAction = () -> {
            snowStepBoots.unregister();
            snowStepBoots.register();
        };
        Objects.requireNonNull(snowStepBoots);
        entryList.add(new Entry("snow_step_boots", "Snow Step Boots", icon, enableAction, snowStepBoots::unregister));
        entryList = this.entries;
        icon = Material.PINK_PETALS;
        enableAction = () -> {
            springStrideBoots.unregister();
            springStrideBoots.register();
        };
        Objects.requireNonNull(springStrideBoots);
        entryList.add(new Entry("spring_stride_boots", "Spring Stride Boots", icon, enableAction, springStrideBoots::unregister));
        entryList = this.entries;
        icon = Material.WHEAT;
        enableAction = () -> {
            labradorBoots.unregister();
            labradorBoots.register();
        };
        Objects.requireNonNull(labradorBoots);
        entryList.add(new Entry("labrador_boots", "Labrador Boots", icon, enableAction, labradorBoots::unregister));
        entryList = this.entries;
        icon = Material.TORCH;
        enableAction = () -> {
            solarTorch.unregister();
            solarTorch.register();
        };
        Objects.requireNonNull(solarTorch);
        entryList.add(new Entry("solar_torch", "Solar Torch", icon, enableAction, solarTorch::unregister));
        entryList = this.entries;
        icon = Material.LANTERN;
        enableAction = () -> {
            lunarLantern.unregister();
            lunarLantern.register();
        };
        Objects.requireNonNull(lunarLantern);
        entryList.add(new Entry("lunar_lantern", "Lunar Lantern", icon, enableAction, lunarLantern::unregister));
        entryList = this.entries;
        icon = Material.BELL;
        enableAction = () -> {
            springBell.unregister();
            springBell.register();
        };
        Objects.requireNonNull(springBell);
        entryList.add(new Entry("spring_bell", "Spring Bell", icon, enableAction, springBell::unregister));
        entryList = this.entries;
        icon = Material.COMPOSTER;
        enableAction = () -> {
            advancedComposter.unregister();
            advancedComposter.register();
        };
        Objects.requireNonNull(advancedComposter);
        entryList.add(new Entry("advanced_composter", "Advanced Composter", icon, enableAction, advancedComposter::unregister));
        entryList = this.entries;
        icon = Material.PACKED_ICE;
        enableAction = () -> {
            golemUpgrades.unregister();
            golemUpgrades.register();
        };
        Objects.requireNonNull(golemUpgrades);
        entryList.add(new Entry("golem_cores", "Golem Cores (Frost/Guardian)", icon, enableAction, golemUpgrades::unregister));
        this.ensureDefaultsAndApply();
    }

    /**
     * 确保配置中存在默认开关项，并把当前配置状态应用到各条目。
     */
    private void ensureDefaultsAndApply() {
        FileConfiguration cfg = this.plugin.getConfig();
        if (!cfg.contains("seasoncraft.enabled")) {
            cfg.set("seasoncraft.enabled", true);
        }

        if (!cfg.contains("seasoncraft.gui_title")) {
            cfg.set("seasoncraft.gui_title", "&bSeasonCraft");
        }

        for (Entry entry : this.entries) {
            String path = "seasoncraft.recipes." + entry.id;
            if (!cfg.contains(path)) {
                cfg.set(path, true);
            }
        }

        this.plugin.saveConfig();

        for (Entry entry : this.entries) {
            this.setEntryEnabled(entry, this.isEnabled(entry.id));
        }

    }

    /**
     * 系统总开关（config: seasoncraft.enabled）。
     */
    private boolean isSystemEnabled() {
        return this.plugin.getConfig().getBoolean("seasoncraft.enabled", true);
    }

    /**
     * 查询某个条目是否启用（config: seasoncraft.recipes.<id>）。
     */
    private boolean isEnabled(String id) {
        return this.plugin.getConfig().getBoolean("seasoncraft.recipes." + id, true);
    }

    /**
     * 持久化某个条目的启用状态。
     */
    private void setEnabled(String id, boolean enabled) {
        this.plugin.getConfig().set("seasoncraft.recipes." + id, enabled);
        this.plugin.saveConfig();
    }

    /**
     * 根据状态执行条目的启用或禁用动作。
     */
    private void setEntryEnabled(Entry entry, boolean enabled) {
        if (enabled) {
            entry.enable.run();
        } else {
            entry.disable.run();
        }

    }

    /**
     * 处理 /seasoncraft：校验权限与系统开关后打开 GUI。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            if (!player.hasPermission("aeternum.seasoncraft")) {
                player.sendMessage("§cNo tienes permiso.");
                return true;
            } else if (!this.isSystemEnabled()) {
                player.sendMessage("§cSeasonCraft está desactivado en config.yml.");
                return true;
            } else {
                this.openGui(player);
                return true;
            }
        } else {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
    }

    /**
     * 构建并打开 27 格的开关界面，居中摆放各条目图标。
     */
    private void openGui(Player player) {
        Holder holder = new Holder();
        int size = 27;
        Inventory inv = Bukkit.createInventory(holder, size, color(this.plugin.getConfig().getString("seasoncraft.gui_title", "&bSeasonCraft")));
        holder.inv = inv;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }

        for (int i = 0; i < size; ++i) {
            inv.setItem(i, filler);
        }

        int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21};

        for (int i = 0; i < this.entries.size() && i < slots.length; ++i) {
            inv.setItem(slots[i], this.buildEntryItem((Entry) this.entries.get(i)));
        }

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 1.4F);
    }

    /**
     * 根据条目当前状态构建图标物品（含 ON/OFF 标记与 PDC 标识）。
     */
    private ItemStack buildEntryItem(Entry entry) {
        boolean enabled = this.isEnabled(entry.id);
        ItemStack it = new ItemStack(entry.icon);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String titleText = entry.title;
            meta.setDisplayName("§e" + titleText);
            List<String> lore = new ArrayList();
            lore.add(" ");
            lore.add("§7" + (enabled ? "§aON" : "§cOFF"));
            lore.add("§8(Click)");
            meta.setLore(lore);
            if (enabled) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
            }

            meta.getPersistentDataContainer().set(this.pdcIdKey, PersistentDataType.STRING, entry.id);
            it.setItemMeta(meta);
        }

        return it;
    }

    /**
     * 处理 GUI 点击：取消默认行为，根据点击物品的 PDC 标识切换对应条目开关并刷新图标。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onInvClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();
        if (clicker instanceof Player player) {
            if (event.getInventory().getHolder() instanceof Holder) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.getType() != Material.AIR) {
                    ItemMeta meta = clicked.getItemMeta();
                    if (meta != null) {
                        String id = (String) meta.getPersistentDataContainer().get(this.pdcIdKey, PersistentDataType.STRING);
                        if (id != null && !id.isEmpty()) {
                            Entry entry = (Entry) this.entries.stream().filter((candidate) -> candidate.id.equals(id)).findFirst().orElse(null);
                            if (entry != null) {
                                boolean newState = !this.isEnabled(id);
                                this.setEnabled(id, newState);
                                this.setEntryEnabled(entry, newState);
                                event.getInventory().setItem(event.getSlot(), this.buildEntryItem(entry));
                                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7F, newState ? 1.6F : 0.9F);
                                player.sendMessage("§bSeasonCraft§7: §e" + entry.title + "§7 -> " + (newState ? "§aON" : "§cOFF"));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 界面关闭监听（当前无需额外清理，仅保留占位）。
     */
    @EventHandler
    public void onInvClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
        }
    }

    /**
     * 把 {@code &} 颜色代码转换为 Minecraft 的 {@code §} 颜色代码。
     */
    private static String color(String text) {
        return text == null ? "" : text.replace("&", "§");
    }

    /**
     * GUI 中的一个可开关条目：id、标题、图标材质与启停动作。
     */
    private static final class Entry {
        final String id;
        final String title;
        final Material icon;
        final Runnable enable;
        final Runnable disable;

        Entry(String id, String title, Material icon, Runnable enable, Runnable disable) {
            super();
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.enable = enable;
            this.disable = disable;
        }
    }

    /**
     * GUI 的 {@link InventoryHolder} 标识，用于在事件中识别本界面。
     */
    private static final class Holder implements InventoryHolder {
        private Inventory inv;

        private Holder() {
            super();
        }

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }
}
