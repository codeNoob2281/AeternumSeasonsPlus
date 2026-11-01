package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.world.BiomeSpoofAdapter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class SeasonBiomeFixSpringJungleCommand implements CommandExecutor, TabCompleter {
    private static final Biome TARGET;
    private static final int STEP_XZ = 4;
    private static final int STEP_Y = 4;
    private final AeternumSeasonsPlugin plugin;
    private final BiomeSpoofAdapter biomeSpoof;

    public SeasonBiomeFixSpringJungleCommand(AeternumSeasonsPlugin plugin, BiomeSpoofAdapter biomeSpoof) {
        super();
        this.plugin = plugin;
        this.biomeSpoof = biomeSpoof;
    }

    public boolean onCommand(final CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("aeternum.command.biomefix")) {
            return true;
        } else {
            boolean allWorlds = false;
            World targetWorld = null;
            int budget = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.restore_budget_chunks_per_tick", 6));
            if (args.length >= 1) {
                String a0 = args[0];
                Integer maybeBudget = this.tryParseInt(a0);
                if (maybeBudget != null) {
                    budget = Math.max(1, maybeBudget);
                } else if (a0.equalsIgnoreCase("all")) {
                    allWorlds = true;
                } else {
                    targetWorld = Bukkit.getWorld(a0);
                    if (targetWorld == null) {
                        sender.sendMessage("§cMundo no encontrado: §f" + a0);
                        return true;
                    }
                }
            }

            if (args.length >= 2) {
                Integer maybeBudget = this.tryParseInt(args[1]);
                if (maybeBudget != null) {
                    budget = Math.max(1, maybeBudget);
                }
            }

            if (!allWorlds && targetWorld == null) {
                if (sender instanceof Player) {
                    Player p = (Player)sender;
                    targetWorld = p.getWorld();
                } else {
                    targetWorld = this.firstNormalWorld();
                    if (targetWorld == null) {
                        sender.sendMessage("§cNo encontré ningún mundo NORMAL cargado.");
                        return true;
                    }
                }
            }

            final boolean wasEnabled = this.biomeSpoof != null && this.biomeSpoof.isEnabled();
            if (this.biomeSpoof != null) {
                this.biomeSpoof.setEnabled(false);
                this.hardResetBiomeSpoofInternals(sender);
            }

            final List<ChunkPos> work = new ArrayList();
            if (allWorlds) {
                for(World w : Bukkit.getWorlds()) {
                    if (w.getEnvironment() == Environment.NORMAL) {
                        for(Chunk ch : w.getLoadedChunks()) {
                            work.add(new ChunkPos(w.getUID(), ch.getX(), ch.getZ()));
                        }
                    }
                }
            } else {
                if (targetWorld.getEnvironment() != Environment.NORMAL) {
                    sender.sendMessage("§eAviso: este mundo no es NORMAL, igual lo proceso (pero lo normal es solo overworld).");
                }

                for(Chunk ch : targetWorld.getLoadedChunks()) {
                    work.add(new ChunkPos(targetWorld.getUID(), ch.getX(), ch.getZ()));
                }
            }

            if (work.isEmpty()) {
                sender.sendMessage("§eNo hay chunks cargados para procesar.");
                if (this.biomeSpoof != null && wasEnabled) {
                    this.biomeSpoof.setEnabled(true);
                }

                return true;
            } else {
                String var10001 = TARGET.name();
                sender.sendMessage("§a[BiomeFix] Forzando bioma §f" + var10001 + "§a en §f" + work.size() + "§a chunks cargados. budget=§f" + budget + "§a/tick");
                final int budgetFinal = budget;
                final boolean allWorldsFinal = allWorlds;
                final World targetWorldFinal = targetWorld;
                (new BukkitRunnable() {
                    int idx = 0;
                    int painted = 0;
                    int skippedUnloaded = 0;
                    int ticks = 0;

                    public void run() {
                        ++this.ticks;
                        int doneThisTick = 0;

                        while(doneThisTick < budgetFinal && this.idx < work.size()) {
                            ChunkPos pos = (ChunkPos)work.get(this.idx++);
                            World w = Bukkit.getWorld(pos.worldId);
                            if (w != null) {
                                if (!w.isChunkLoaded(pos.cx, pos.cz)) {
                                    ++this.skippedUnloaded;
                                    ++doneThisTick;
                                } else {
                                    boolean changed = SeasonBiomeFixSpringJungleCommand.this.applyBiomeGrid(w, pos.cx, pos.cz, SeasonBiomeFixSpringJungleCommand.TARGET);
                                    if (changed) {
                                        ++this.painted;
                                    }

                                    ++doneThisTick;
                                }
                            }
                        }

                        if (this.ticks % 20 == 0) {
                            int var10001 = this.idx;
                            sender.sendMessage("§a[BiomeFix] Progreso: §f" + var10001 + "§7/§f" + work.size() + " §a| Pintados: §f" + this.painted + " §a| Saltados(unloaded): §f" + this.skippedUnloaded);
                        }

                        if (this.idx >= work.size()) {
                            this.cancel();
                            String scope = allWorldsFinal ? "ALL_NORMAL_WORLDS" : (targetWorldFinal != null ? targetWorldFinal.getName() : "WORLD");
                            sender.sendMessage("§a[BiomeFix] Terminado (" + scope + "). Pintados: §f" + this.painted + "§a | Saltados(unloaded): §f" + this.skippedUnloaded);
                            if (SeasonBiomeFixSpringJungleCommand.this.biomeSpoof != null && wasEnabled) {
                                SeasonBiomeFixSpringJungleCommand.this.biomeSpoof.setEnabled(true);
                                sender.sendMessage("§a[BiomeFix] BiomeSpoofAdapter reactivado.");
                            }

                            sender.sendMessage("§eTip: si quieres que “sea primavera” también, corre §f/season set SPRING");
                        }

                    }
                }).runTaskTimer(this.plugin, 1L, 1L);
                return true;
            }
        }
    }

    private boolean applyBiomeGrid(World w, int cx, int cz, Biome target) {
        int bx = cx << 4;
        int bz = cz << 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        boolean anyChange = false;

        for(int x = 0; x < 16; x += 4) {
            for(int z = 0; z < 16; z += 4) {
                for(int y = minY; y < maxY; y += 4) {
                    Biome cur = w.getBiome(bx + x, y, bz + z);
                    if (cur != target) {
                        w.setBiome(bx + x, y, bz + z, target);
                        anyChange = true;
                    }
                }
            }
        }

        if (anyChange) {
            w.refreshChunk(cx, cz);
        }

        return anyChange;
    }

    private void hardResetBiomeSpoofInternals(CommandSender sender) {
        if (this.biomeSpoof != null) {
            try {
                Field cold = BiomeSpoofAdapter.class.getDeclaredField("COLD_CHUNKS");
                cold.setAccessible(true);
                Object coldSet = cold.get((Object)null);
                if (coldSet instanceof Set) {
                    Set<?> s = (Set)coldSet;
                    s.clear();
                }

                Field spoofed = BiomeSpoofAdapter.class.getDeclaredField("spoofed");
                spoofed.setAccessible(true);
                Object spoofedSet = spoofed.get(this.biomeSpoof);
                if (spoofedSet instanceof Set) {
                    Set<?> s = (Set)spoofedSet;
                    s.clear();
                }

                Field backups = BiomeSpoofAdapter.class.getDeclaredField("backups");
                backups.setAccessible(true);
                Object backupsMap = backups.get(this.biomeSpoof);
                if (backupsMap instanceof Map) {
                    Map<?, ?> m = (Map)backupsMap;
                    m.clear();
                }

                this.tryClearFieldMap("nudgeQueue");
                this.tryClearFieldMap("nudgeLast");
                sender.sendMessage("§a[BiomeFix] Cache del BiomeSpoof limpiada (cold/spoofed/backups).");
            } catch (Throwable t) {
                sender.sendMessage("§e[BiomeFix] No pude limpiar caches por reflexión: " + t.getMessage());
            }

        }
    }

    private void tryClearFieldMap(String fieldName) {
        try {
            Field f = BiomeSpoofAdapter.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(this.biomeSpoof);
            if (v instanceof Map<?, ?> m) {
                m.clear();
            }
        } catch (Throwable var5) {
        }

    }

    private World firstNormalWorld() {
        for(World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == Environment.NORMAL) {
                return w;
            }
        }

        return null;
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception var3) {
            return null;
        }
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
        return Collections.emptyList();
    }

    static {
        TARGET = Biome.SAVANNA;
    }

    private static record ChunkPos(UUID worldId, int cx, int cz) {
        private ChunkPos {
        }
    }
}
