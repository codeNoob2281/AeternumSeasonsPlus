package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Guilds/GPFlags 兼容层。
 *
 * <p>通过反射探测服务器上是否存在 GPFlags 插件，若存在则解析其
 * {@code NoBlockForm}、{@code NoIceForm}、{@code NoSnowForm} 三个旗帜定义，并缓存用于查询的
 * {@code getFlagInstanceAtLocation} 方法。之后由 {@link WinterWorldGuardHelper} 在决定是否允许
 * 积雪/结冰时调用，避免在受保护区域产生方块形态变化。</p>
 *
 * <p>全部交互均通过反射完成，因此不硬依赖 GPFlags；一旦运行期发生 API 异常会自动关闭 hook 并记录警告。</p>
 */
final class GPFlagsCompat {
    private static AeternumSeasonsPlugin plugin;
    private static boolean hooked;
    /** 反射缓存：旗帜定义上的 {@code getFlagInstanceAtLocation(Location, Player)} 方法。 */
    private static Method getFlagAtLocation;
    /** NoBlockForm 旗帜定义。 */
    private static Object noBlockForm;
    /** NoIceForm 旗帜定义。 */
    private static Object noIceForm;
    /** NoSnowForm 旗帜定义。 */
    private static Object noSnowForm;

    private GPFlagsCompat() {
        super();
    }

    /** 初始化兼容层：重置状态并尝试与 GPFlags 建立反射 hook。 */
    static void init(AeternumSeasonsPlugin owner) {
        plugin = owner;
        hooked = false;
        getFlagAtLocation = null;
        noBlockForm = null;
        noIceForm = null;
        noSnowForm = null;
        Plugin gpFlags = Bukkit.getPluginManager().getPlugin("GPFlags");
        if (gpFlags != null && gpFlags.isEnabled()) {
            try {
                Object manager = gpFlags.getClass().getMethod("getFlagManager").invoke(gpFlags);
                Method getDefinition = manager.getClass().getMethod("getFlagDefinitionByName", String.class);
                noBlockForm = getDefinition.invoke(manager, "NoBlockForm");
                noIceForm = getDefinition.invoke(manager, "NoIceForm");
                noSnowForm = getDefinition.invoke(manager, "NoSnowForm");
                Object sample = noBlockForm != null ? noBlockForm : (noIceForm != null ? noIceForm : noSnowForm);
                if (sample == null) {
                    owner.getLogger().warning("[AeternumSeasons] GPFlags detected, but block-form flags were not registered.");
                    return;
                }

                getFlagAtLocation = sample.getClass().getMethod("getFlagInstanceAtLocation", Location.class, Player.class);
                hooked = true;
                owner.getLogger().info("[AeternumSeasons] GPFlags detected; respecting NoBlockForm, NoIceForm and NoSnowForm.");
            } catch (Throwable error) {
                owner.getLogger().warning("[AeternumSeasons] Could not hook GPFlags: " + error.getMessage());
            }

        } else {
            owner.getLogger().info("[AeternumSeasons] GPFlags not found.");
        }
    }

    /** 判断该方块是否允许形成积雪（未受 NoBlockForm / NoSnowForm 限制）。 */
    static boolean canSnowForm(Block block) {
        return !isActive(noBlockForm, block) && !isActive(noSnowForm, block);
    }

    /** 判断该方块是否允许形成结冰（未受 NoBlockForm / NoIceForm 限制）。 */
    static boolean canIceForm(Block block) {
        return !isActive(noBlockForm, block) && !isActive(noIceForm, block);
    }

    /** 查询给定旗帜定义在方块位置是否生效；发生异常时关闭 hook 并返回 false。 */
    private static boolean isActive(Object definition, Block block) {
        if (hooked && definition != null && block != null) {
            try {
                return getFlagAtLocation.invoke(definition, block.getLocation(), null) != null;
            } catch (Throwable error) {
                hooked = false;
                if (plugin != null) {
                    plugin.getLogger().warning("[AeternumSeasons] GPFlags hook disabled after an API error: " + error.getMessage());
                }

                return false;
            }
        } else {
            return false;
        }
    }
}
