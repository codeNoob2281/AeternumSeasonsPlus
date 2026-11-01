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

public final class FoodRecipeBookLoot implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager lang;

    public FoodRecipeBookLoot(AeternumSeasonsPlugin plugin, LanguageManager lang) {
        super();
        this.plugin = plugin;
        this.lang = lang;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private String trPlayerOrServer(Player p, String key) {
        return p != null ? this.lang.tr(p, key) : this.lang.trServer(key);
    }

    private boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private String normalizeForPaginator(String text) {
        if (text == null) {
            return null;
        } else {
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();

            for(int i = 0; i < lines.length; ++i) {
                String line = lines[i];
                if (line.trim().isEmpty()) {
                    line = "§r";
                }

                sb.append(line);
                if (i < lines.length - 1) {
                    sb.append('\n');
                }
            }

            return sb.toString();
        }
    }

    private ItemStack buildRecipeBook(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            return book;
        } else {
            String base = "guide_food.";
            meta.setTitle(this.trPlayerOrServer(p, base + "title"));
            meta.setAuthor(this.trPlayerOrServer(p, base + "author"));
            BookPaginator paginator = new BookPaginator();
            String page1 = this.normalizeForPaginator(this.trPlayerOrServer(p, base + "page1"));
            String page2 = this.normalizeForPaginator(this.trPlayerOrServer(p, base + "page2"));
            String page3 = this.normalizeForPaginator(this.trPlayerOrServer(p, base + "page3"));
            String page4 = this.normalizeForPaginator(this.trPlayerOrServer(p, base + "page4"));
            String page5 = this.normalizeForPaginator(this.trPlayerOrServer(p, base + "page5"));
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

    @EventHandler
    public void onLootGenerate(LootGenerateEvent e) {
        LootTable table = e.getLootTable();
        if (table != null) {
            NamespacedKey key = table.getKey();
            if (key != null) {
                String path = key.getKey();
                List<ItemStack> loot = e.getLoot();
                Random rnd = ThreadLocalRandom.current();
                Entity var9 = e.getEntity();
                Player var10000;
                if (var9 instanceof Player) {
                    Player p = (Player)var9;
                    var10000 = p;
                } else {
                    var10000 = null;
                }

                Player looter = var10000;
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

                    if (rnd.nextDouble() < bookChance) {
                        loot.add(this.buildRecipeBook(looter));
                    }

                }
            }
        }
    }
}
