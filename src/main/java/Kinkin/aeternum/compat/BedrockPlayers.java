package kinkin.aeternum.compat;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 基岩版玩家识别工具。
 *
 * <p>通过反射依次探测 Floodgate 与 Geyser 两套 API，判断给定玩家是否为基岩版（Bedrock）
 * 客户端玩家。两套插件均未安装或探测失败时，统一按 Java 版玩家处理（返回 false）。</p>
 *
 * <p>依赖：{@code org.geysermc.floodgate.api.FloodgateApi} 或
 * {@code org.geysermc.geyser.api.GeyserApi}（均为可选运行时依赖）。</p>
 */
public final class BedrockPlayers {
    private BedrockPlayers() {
        super();
    }

    /**
     * 判断玩家是否为基岩版玩家：先尝试 Floodgate，再尝试 Geyser。
     */
    public static boolean isBedrock(Player player) {
        return check("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", player) || check("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", player);
    }

    /**
     * 反射调用指定 API 的无参访问器获得实例，再以玩家 UUID 调用判定方法。
     *
     * @param type     API 类全限定名
     * @param accessor 获取 API 单例的方法名
     * @param method   接收 UUID、返回布尔值的方法名
     * @return 判定结果；类不存在、方法缺失或调用异常时返回 false
     */
    private static boolean check(String type, String accessor, String method, Player player) {
        try {
            Class<?> api = Class.forName(type);
            Object instance = api.getMethod(accessor).invoke((Object) null);
            return Boolean.TRUE.equals(api.getMethod(method, UUID.class).invoke(instance, player.getUniqueId()));
        } catch (LinkageError | ReflectiveOperationException error) {
            return false;
        }
    }
}
