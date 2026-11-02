package Kinkin.aeternum.food;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.lang.LanguageManager;
import Kinkin.aeternum.util.BookPaginator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.loot.LootTable;

/**
 * 食物配方书掉落监听器（Food Recipe Book Loot）。
 *
 * <p>监听 {@link LootGenerateEvent}：当战利品表为村庄、废弃矿井、林地府邸或要塞宝箱时，
 * 按各自概率向战利品中加入一本“食物配方书”。配方书的标题、作者与各页内容均通过
 * {@link LanguageManager} 按玩家语言本地化，并借助 {@link BookPaginator} 自动分页。</p>
 *
 * <p>依赖 {@link SeasonFoods} 所配套的 {@link VillageFoodLoot} 判定村庄宝箱。</p>
 */
public final class FoodRecipeBookLoot implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager lang;

    public FoodRecipeBookLoot(AeternumSeasonsPlugin plugin, LanguageManager lang) {
        super();
        this.plugin = plugin;
        this.lang = lang;
    }

    /** 把本监听器注册到 Bukkit 事件系统。 */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /** 从 Bukkit 事件系统注销本监听器的全部处理器。 */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** 按玩家语言翻译；玩家为 null 时退回服务端默认语言。 */
    private String trPlayerOrServer(Player player, String key) {
        return player != null ? this.lang.tr(player, key) : this.lang.trServer(key);
    }

    /** 判断字符串是否为 null 或空串。 */
    private boolean isNullOrEmpty(String text) {
        return text == null || text.isEmpty();
    }

    /**
     * 为分页器规整文本：按行拆分，把空白行替换为颜色重置符 {@code §r} 后再拼接，
     * 避免分页时空行被丢弃。
     */
    private String normalizeForPaginator(String text) {
        if (text == null) {
            return null;
        } else {
            String[] lines = text.split("\n", -1);
            StringBuilder builder = new StringBuilder();

            for(int lineIndex = 0; lineIndex < lines.length; ++lineIndex) {
                String line = lines[lineIndex];
                if (line.trim().isEmpty()) {
                    line = "§r";
                }

                builder.append(line);
                if (lineIndex < lines.length - 1) {
                    builder.append('\n');
                }
            }

            return builder.toString();
        }
    }

    /**
     * 按玩家语言构建一本已分页的成书配方书。
     *
     * @param player 目标玩家，可为 {@code null}（此时使用服务端默认语言）
     * @return 构建好的成书；若无法取得书籍 meta 则返回未修改的书本物品
     */
    private ItemStack buildRecipeBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            return book;
        } else {
            String base = "guide_food.";
            meta.setTitle(this.trPlayerOrServer(player, base + "title"));
            meta.setAuthor(this.trPlayerOrServer(player, base + "author"));
            BookPaginator paginator = new BookPaginator();
            String page1 = this.normalizeForPaginator(this.trPlayerOrServer(player, base + "page1"));
            String page2 = this.normalizeForPaginator(this.trPlayerOrServer(player, base + "page2"));
            String page3 = this.normalizeForPaginator(this.trPlayerOrServer(player, base + "page3"));
            String page4 = this.normalizeForPaginator(this.trPlayerOrServer(player, base + "page4"));
            String page5 = this.normalizeForPaginator(this.trPlayerOrServer(player, base + "page5"));
            if (!this.isNullOrEmpty(page1)) {
                paginator.addText(page1);
                paginator.newPage();
            }

            if (!this.isNullOrEmpty(page2)) {
                paginator.addText(page2);
                paginator.newPage();
            }

            if (!this.isNullOrEmpty(page3)) {
                paginator.addText(page3);
                paginator.newPage();
            }

            if (!this.isNullOrEmpty(page4)) {
                paginator.addText(page4);
                paginator.newPage();
            }

            if (!this.isNullOrEmpty(page5)) {
                paginator.addText(page5);
            }

            List<String> pages = paginator.build();
            if (!pages.isEmpty()) {
                meta.setPages(pages);
            }

            book.setItemMeta(meta);
            return book;
        }
    }

    /**
     * 监听战利品生成事件：依据战利品表类型决定是否额外加入一本食物配方书。
     *
     * <p>村庄、废弃矿井、林地府邸、要塞宝箱各有不同的掉落概率；若开箱者是玩家，
     * 则用其语言构建书籍内容。</p>
     */
    @EventHandler
    public void onLootGenerate(LootGenerateEvent e) {
        LootTable table = e.getLootTable();
        if (table != null) {
            NamespacedKey key = table.getKey();
            if (key != null) {
                String path = key.getKey();
                List<ItemStack> loot = e.getLoot();
                Random random = ThreadLocalRandom.current();
                Entity sourceEntity = e.getEntity();
                Player playerOrNull;
                if (sourceEntity instanceof Player) {
                    Player player = (Player)sourceEntity;
                    playerOrNull = player;
                } else {
                    playerOrNull = null;
                }

                Player looter = playerOrNull;
                boolean isVillage = VillageFoodLoot.matches(key);
                boolean isMineshaft = path.equals("chests/abandoned_mineshaft");
                boolean isMansion = path.equals("chests/woodland_mansion");
                boolean isStronghold = path.startsWith("chests/stronghold_");
                if (isVillage || isMineshaft || isMansion || isStronghold) {
                    double bookChance;
                    if (isVillage) {
                        bookChance = 0.15;
                    } else if (isMineshaft) {
                        bookChance = (double)0.25F;
                    } else if (isMansion) {
                        bookChance = 0.3;
                    } else {
                        bookChance = 0.4;
                    }

                    if (random.nextDouble() < bookChance) {
                        loot.add(this.buildRecipeBook(looter));
                    }

                }
            }
        }
    }
}
