package kinkin.aeternum.food;

import org.bukkit.NamespacedKey;

/**
 * 村庄食物战利品判定工具（Village Food Loot）。
 *
 * <p>提供静态方法判断某个战利品表是否属于村庄宝箱，供食物配方书掉落逻辑复用。</p>
 */
public final class VillageFoodLoot {
    private VillageFoodLoot() {
        super();
    }

    /**
     * 判断给定战利品表的键是否以 {@code chests/village/} 开头（即村庄宝箱）。
     */
    public static boolean matches(NamespacedKey key) {
        return key != null && key.getKey().startsWith("chests/village/");
    }
}
