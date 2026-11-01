package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public final class FrostBiomeFixer implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;

    public FrostBiomeFixer(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (w.getName().equalsIgnoreCase("aeternum_frost")) {
            Chunk chunk = e.getChunk();
            int worldMinY = w.getMinHeight();
            int worldMaxY = w.getMaxHeight();

            for(int cx = 0; cx < 16; ++cx) {
                for(int cz = 0; cz < 16; ++cz) {
                    int x = chunk.getX() * 16 + cx;
                    int z = chunk.getZ() * 16 + cz;
                    Biome current = w.getBiome(x, worldMinY, z);
                    if (!this.isColdBiome(current)) {
                        for(int y = worldMinY; y < worldMaxY; y += 8) {
                            w.setBiome(x, y, z, Biome.SNOWY_PLAINS);
                        }
                    }
                }
            }

        }
    }

    private boolean isColdBiome(Biome b) {
        return b == Biome.SNOWY_PLAINS
                || b == Biome.SNOWY_TAIGA
                || b == Biome.GROVE
                || b == Biome.SNOWY_SLOPES
                || b == Biome.JAGGED_PEAKS
                || b == Biome.FROZEN_PEAKS
                || b == Biome.FROZEN_RIVER
                || b == Biome.FROZEN_OCEAN
                || b == Biome.DEEP_FROZEN_OCEAN;
    }
}
