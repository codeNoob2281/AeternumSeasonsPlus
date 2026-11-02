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

/**
 * 生物群系修复命令（/seasonbiomefix）。
 *
 * <p>把指定世界（或全部 NORMAL 世界）已加载区块的生物群系强制重绘为 {@link #TARGET}
 * 目标群系，用于修复季节切换或生物群系伪装后残留的错误群系。命令以 BukkitRunnable
 * 分帧执行，每 tick 处理有限数量的区块（budget），避免主线程卡顿。</p>
 *
 * <p>执行前会关闭 {@link BiomeSpoofAdapter} 并通过反射清空其内部缓存
 * （COLD_CHUNKS/spoofed/backups 等），结束后按原状恢复启用状态。</p>
 */
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

    /**
     * 处理 /seasonbiomefix 命令。
     *
     * <p>参数 {@code args[0]} 可为 {@code all}、世界名或数字（每 tick 区块预算）；
     * {@code args[1]} 可作为额外的预算覆盖。随后收集目标世界的已加载区块，
     * 逐 tick 重绘生物群系并输出进度。</p>
     */
    public boolean onCommand(final CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("aeternum.command.biomefix")) {
            return true;
        } else {
            boolean allWorlds = false;
            World targetWorld = null;
            int budget = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.restore_budget_chunks_per_tick", 6));
            if (args.length >= 1) {
                String firstArg = args[0];
                Integer maybeBudget = this.tryParseInt(firstArg);
                if (maybeBudget != null) {
                    budget = Math.max(1, maybeBudget);
                } else if (firstArg.equalsIgnoreCase("all")) {
                    allWorlds = true;
                } else {
                    targetWorld = Bukkit.getWorld(firstArg);
                    if (targetWorld == null) {
                        sender.sendMessage("§cMundo no encontrado: §f" + firstArg);
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
                    Player player = (Player)sender;
                    targetWorld = player.getWorld();
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
                for(World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == Environment.NORMAL) {
                        for(Chunk chunk : world.getLoadedChunks()) {
                            work.add(new ChunkPos(world.getUID(), chunk.getX(), chunk.getZ()));
                        }
                    }
                }
            } else {
                if (targetWorld.getEnvironment() != Environment.NORMAL) {
                    sender.sendMessage("§eAviso: este mundo no es NORMAL, igual lo proceso (pero lo normal es solo overworld).");
                }

                for(Chunk chunk : targetWorld.getLoadedChunks()) {
                    work.add(new ChunkPos(targetWorld.getUID(), chunk.getX(), chunk.getZ()));
                }
            }

            if (work.isEmpty()) {
                sender.sendMessage("§eNo hay chunks cargados para procesar.");
                if (this.biomeSpoof != null && wasEnabled) {
                    this.biomeSpoof.setEnabled(true);
                }

                return true;
            } else {
                String targetBiomeName = TARGET.name();
                sender.sendMessage("§a[BiomeFix] Forzando bioma §f" + targetBiomeName + "§a en §f" + work.size() + "§a chunks cargados. budget=§f" + budget + "§a/tick");
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
                            World world = Bukkit.getWorld(pos.worldId);
                            if (world != null) {
                                if (!world.isChunkLoaded(pos.cx, pos.cz)) {
                                    ++this.skippedUnloaded;
                                    ++doneThisTick;
                                } else {
                                    boolean changed = SeasonBiomeFixSpringJungleCommand.this.applyBiomeGrid(world, pos.cx, pos.cz, SeasonBiomeFixSpringJungleCommand.TARGET);
                                    if (changed) {
                                        ++this.painted;
                                    }

                                    ++doneThisTick;
                                }
                            }
                        }

                        if (this.ticks % 20 == 0) {
                            int processedCount = this.idx;
                            sender.sendMessage("§a[BiomeFix] Progreso: §f" + processedCount + "§7/§f" + work.size() + " §a| Pintados: §f" + this.painted + " §a| Saltados(unloaded): §f" + this.skippedUnloaded);
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

    /**
     * 按 4×4×4 网格重绘单个区块的生物群系，必要时刷新区块。
     *
     * @return 是否有任意采样点的实际群系发生变化
     */
    private boolean applyBiomeGrid(World world, int cx, int cz, Biome target) {
        int bx = cx << 4;
        int bz = cz << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        boolean anyChange = false;

        for(int x = 0; x < 16; x += 4) {
            for(int z = 0; z < 16; z += 4) {
                for(int y = minY; y < maxY; y += 4) {
                    Biome currentBiome = world.getBiome(bx + x, y, bz + z);
                    if (currentBiome != target) {
                        world.setBiome(bx + x, y, bz + z, target);
                        anyChange = true;
                    }
                }
            }
        }

        if (anyChange) {
            world.refreshChunk(cx, cz);
        }

        return anyChange;
    }

    /** 通过反射清空 {@link BiomeSpoofAdapter} 的静态/实例缓存，避免修复期间被伪装数据干扰。 */
    private void hardResetBiomeSpoofInternals(CommandSender sender) {
        if (this.biomeSpoof != null) {
            try {
                Field cold = BiomeSpoofAdapter.class.getDeclaredField("COLD_CHUNKS");
                cold.setAccessible(true);
                Object coldSet = cold.get((Object)null);
                if (coldSet instanceof Set) {
                    Set<?> coldChunks = (Set)coldSet;
                    coldChunks.clear();
                }

                Field spoofed = BiomeSpoofAdapter.class.getDeclaredField("spoofed");
                spoofed.setAccessible(true);
                Object spoofedSet = spoofed.get(this.biomeSpoof);
                if (spoofedSet instanceof Set) {
                    Set<?> spoofedChunks = (Set)spoofedSet;
                    spoofedChunks.clear();
                }

                Field backups = BiomeSpoofAdapter.class.getDeclaredField("backups");
                backups.setAccessible(true);
                Object backupsMap = backups.get(this.biomeSpoof);
                if (backupsMap instanceof Map) {
                    Map<?, ?> backupsByKey = (Map)backupsMap;
                    backupsByKey.clear();
                }

                this.tryClearFieldMap("nudgeQueue");
                this.tryClearFieldMap("nudgeLast");
                sender.sendMessage("§a[BiomeFix] Cache del BiomeSpoof limpiada (cold/spoofed/backups).");
            } catch (Throwable error) {
                sender.sendMessage("§e[BiomeFix] No pude limpiar caches por reflexión: " + error.getMessage());
            }

        }
    }

    /** 反射清空 BiomeSpoofAdapter 上某个 Map 类型字段；任何异常都静默忽略。 */
    private void tryClearFieldMap(String fieldName) {
        try {
            Field field = BiomeSpoofAdapter.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(this.biomeSpoof);
            if (value instanceof Map<?, ?> map) {
                map.clear();
            }
        } catch (Throwable ignored) {
        }

    }

    /** 返回第一个已加载的 NORMAL（主世界）维度，找不到返回 null。 */
    private World firstNormalWorld() {
        for(World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == Environment.NORMAL) {
                return world;
            }
        }

        return null;
    }

    /** 尝试把字符串解析为整数，失败返回 null。 */
    private Integer tryParseInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 本命令不提供参数补全。 */
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        return Collections.emptyList();
    }

    static {
        TARGET = Biome.SAVANNA;
    }

    /** 待处理区块的坐标（世界 UUID + 区块 x/z）。 */
    private static record ChunkPos(UUID worldId, int cx, int cz) {
        private ChunkPos {
        }
    }
}
