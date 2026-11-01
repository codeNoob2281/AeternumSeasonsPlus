package Kinkin.aeternum.food;

import org.bukkit.NamespacedKey;

public final class VillageFoodLoot {
    private VillageFoodLoot() {
        super();
    }

    public static boolean matches(NamespacedKey key) {
        return key != null && key.getKey().startsWith("chests/village/");
    }
}
