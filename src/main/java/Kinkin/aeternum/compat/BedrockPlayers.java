package Kinkin.aeternum.compat;

import java.util.UUID;
import org.bukkit.entity.Player;

public final class BedrockPlayers {
    private BedrockPlayers() {
        super();
    }

    public static boolean isBedrock(Player player) {
        return check("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", player) || check("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", player);
    }

    private static boolean check(String type, String accessor, String method, Player player) {
        try {
            Class<?> api = Class.forName(type);
            Object instance = api.getMethod(accessor).invoke((Object)null);
            return Boolean.TRUE.equals(api.getMethod(method, UUID.class).invoke(instance, player.getUniqueId()));
        } catch (LinkageError | ReflectiveOperationException var6) {
            return false;
        }
    }
}
