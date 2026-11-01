package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class GPFlagsCompat {
    private static AeternumSeasonsPlugin plugin;
    private static boolean hooked;
    private static Method getFlagAtLocation;
    private static Object noBlockForm;
    private static Object noIceForm;
    private static Object noSnowForm;

    private GPFlagsCompat() {
        super();
    }

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

    static boolean canSnowForm(Block block) {
        return !isActive(noBlockForm, block) && !isActive(noSnowForm, block);
    }

    static boolean canIceForm(Block block) {
        return !isActive(noBlockForm, block) && !isActive(noIceForm, block);
    }

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
