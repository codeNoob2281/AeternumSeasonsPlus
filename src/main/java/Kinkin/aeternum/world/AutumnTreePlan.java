package kinkin.aeternum.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 秋季树木扫描计划（AutumnTreePlan）。
 *
 * <p>给定一棵树的起始方块，本类通过分阶段广度优先搜索（BFS）逐步收集该树的所有原木与树叶坐标，
 * 供秋季换色逻辑使用。搜索被拆成可中断的分步执行：每次 {@link #advance(BooleanSupplier)}
 * 只消耗有限的预算（由调用方通过 {@code takeBudget} 供给），避免一次性卡住服务器主线程。</p>
 *
 * <p>搜索阶段依次为：{@code FIND_LOG}（从起始点寻找第一个原木）→ {@code LOGS}（收集相连原木并确定锚点）
 * → {@code LEAVES}（从原木周围收集自然树叶）→ {@code DONE}；任一约束（区块未加载、超出上限等）
 * 会使其转为 {@code ABORTED}。依赖 Bukkit 的 {@link World}/{@link Block} 与方块数据 {@link Leaves}。</p>
 */
final class AutumnTreePlan {
    static final int MAX_LEAVES = 2048;
    private static final int MAX_LOGS = 1024;
    private static final int[][] DIRECTIONS = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    final World world;
    final Material original;
    final Pos seed;
    private final Function<Block, Material> originalType;
    private final ArrayDeque<Node> queue = new ArrayDeque();
    private final Set<Pos> visited = new HashSet();
    private final Set<Pos> logs = new LinkedHashSet();
    private final Set<Pos> leaves = new LinkedHashSet();
    private Phase phase;
    private Pos anchor;

    AutumnTreePlan(Block first, Material original, Function<Block, Material> originalType) {
        super();
        this.phase = AutumnTreePlan.Phase.FIND_LOG;
        this.world = first.getWorld();
        this.original = original;
        this.originalType = originalType;
        this.seed = new Pos(first.getX(), first.getY(), first.getZ());
        this.offer(this.seed, 0);
    }

    boolean finished() {
        return this.phase == AutumnTreePlan.Phase.DONE || this.phase == AutumnTreePlan.Phase.ABORTED;
    }

    boolean complete() {
        return this.phase == AutumnTreePlan.Phase.DONE;
    }

    Set<Pos> leaves() {
        return Collections.unmodifiableSet(this.leaves);
    }

    Pos anchor() {
        return this.anchor;
    }

    /**
     * 推进一次搜索，直到预算耗尽或阶段切换。
     *
     * <p>每次取出队首节点并根据当前阶段判断其是否为原木/树叶，从而扩展队列。
     * {@code takeBudget} 返回 {@code false} 时立即让出，等待下一次调用继续。</p>
     *
     * @param takeBudget 预算供给器，每次处理一个节点前调用，返回是否仍有预算
     */
    void advance(BooleanSupplier takeBudget) {
        treeAdvanceLoop:
        while (true) {
            if (!this.finished()) {
                if (this.queue.isEmpty()) {
                    if (this.phase == AutumnTreePlan.Phase.LOGS) {
                        this.phase = AutumnTreePlan.Phase.LEAVES;
                        this.visited.clear();
                        Iterator logIterator = this.logs.iterator();

                        while (true) {
                            if (!logIterator.hasNext()) {
                                continue treeAdvanceLoop;
                            }

                            Pos log = (Pos) logIterator.next();

                            for (int[] d : DIRECTIONS) {
                                this.offer(log.offset(d), 1);
                            }
                        }
                    }

                    this.phase = this.phase == AutumnTreePlan.Phase.LEAVES ? AutumnTreePlan.Phase.DONE : AutumnTreePlan.Phase.ABORTED;
                } else {
                    if (!takeBudget.getAsBoolean()) {
                        return;
                    }

                    Node node = (Node) this.queue.removeFirst();
                    Pos pos = node.pos();
                    if (pos.y() < this.world.getMinHeight() || pos.y() >= this.world.getMaxHeight()) {
                        continue;
                    }

                    if (!this.world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                        this.phase = AutumnTreePlan.Phase.ABORTED;
                    } else {
                        Block block = this.world.getBlockAt(pos.x(), pos.y(), pos.z());
                        boolean log = isLog(block.getType(), this.original);
                        if (this.phase == AutumnTreePlan.Phase.FIND_LOG) {
                            if (log) {
                                this.phase = AutumnTreePlan.Phase.LOGS;
                                this.queue.clear();
                                this.visited.clear();
                                this.offer(pos, 0);
                                continue;
                            }

                            if (node.depth() >= 7 || !this.naturalLeaf(block)) {
                                continue;
                            }

                            if (this.visited.size() <= 4096) {
                                int[][] findDirections = DIRECTIONS;
                                int findDirectionCount = findDirections.length;
                                int findDirectionIndex = 0;

                                while (true) {
                                    if (findDirectionIndex >= findDirectionCount) {
                                        continue treeAdvanceLoop;
                                    }

                                    int[] d = findDirections[findDirectionIndex];
                                    this.offer(pos.offset(d), node.depth() + 1);
                                    ++findDirectionIndex;
                                }
                            }

                            this.phase = AutumnTreePlan.Phase.ABORTED;
                        } else if (this.phase == AutumnTreePlan.Phase.LOGS) {
                            if (!log) {
                                continue;
                            }

                            this.logs.add(pos);
                            if (this.logs.size() <= 1024) {
                                if (this.anchor == null || compareAnchor(pos, this.anchor) < 0) {
                                    this.anchor = pos;
                                }

                                int[][] logDirections = DIRECTIONS;
                                int logDirectionCount = logDirections.length;
                                int logDirectionIndex = 0;

                                while (true) {
                                    if (logDirectionIndex >= logDirectionCount) {
                                        continue treeAdvanceLoop;
                                    }

                                    int[] d = logDirections[logDirectionIndex];
                                    this.offer(pos.offset(d), 0);
                                    ++logDirectionIndex;
                                }
                            }

                            this.phase = AutumnTreePlan.Phase.ABORTED;
                        } else {
                            if (log || !this.naturalLeaf(block)) {
                                continue;
                            }

                            Leaves data = (Leaves) block.getBlockData();
                            if (data.getDistance() < node.depth()) {
                                continue;
                            }

                            this.leaves.add(pos);
                            if (this.leaves.size() <= 2048) {
                                if (node.depth() >= 6) {
                                    continue;
                                }

                                int[][] leafDirections = DIRECTIONS;
                                int leafDirectionCount = leafDirections.length;
                                int leafDirectionIndex = 0;

                                while (true) {
                                    if (leafDirectionIndex >= leafDirectionCount) {
                                        continue treeAdvanceLoop;
                                    }

                                    int[] d = leafDirections[leafDirectionIndex];
                                    this.offer(pos.offset(d), node.depth() + 1);
                                    ++leafDirectionIndex;
                                }
                            }

                            this.phase = AutumnTreePlan.Phase.ABORTED;
                        }
                    }
                }
            }

            return;
        }
    }

    /**
     * 将坐标入队（仅当尚未访问过时），并记录已访问集合以防止重复。
     */
    private void offer(Pos pos, int depth) {
        if (this.visited.add(pos)) {
            this.queue.addLast(new Node(pos, depth));
        }

    }

    /**
     * 判断方块是否为“自然”树叶：即其原始材质与目标树叶一致，且是不带 persistent 标记的树叶方块。
     */
    private boolean naturalLeaf(Block block) {
        boolean result;
        if (this.originalType.apply(block) == this.original) {
            BlockData blockData = block.getBlockData();
            if (blockData instanceof Leaves) {
                Leaves data = (Leaves) blockData;
                if (!data.isPersistent()) {
                    result = true;
                    return result;
                }
            }
        }

        result = false;
        return result;
    }

    /**
     * 依据世界种子与锚点坐标计算一个稳定的调色板索引，用于选取该树的秋季颜色。
     */
    static int paletteIndex(long seed, Pos anchor, int size) {
        long hash = seed ^ (long) anchor.x() * 341873128712L ^ (long) anchor.z() * 132897987541L;
        hash ^= hash >>> 33;
        return Math.floorMod((int) hash, size);
    }

    /**
     * 比较两个锚点的先后顺序：先比 Y，再比 X，最后比 Z，用于选出树的代表锚点。
     */
    private static int compareAnchor(Pos a, Pos b) {
        int y = Integer.compare(a.y(), b.y());
        if (y != 0) {
            return y;
        } else {
            int x = Integer.compare(a.x(), b.x());
            return x != 0 ? x : Integer.compare(a.z(), b.z());
        }
    }

    /**
     * 根据树叶材质推断其所属的原木材质集合，判断给定方块是否为该树的原木。
     */
    private static boolean isLog(Material log, Material leaf) {
        return leaf == Material.SPRUCE_LEAVES ? log == Material.SPRUCE_LOG || log == Material.SPRUCE_WOOD || log == Material.STRIPPED_SPRUCE_LOG || log == Material.STRIPPED_SPRUCE_WOOD : leaf == Material.BIRCH_LEAVES && (log == Material.BIRCH_LOG || log == Material.BIRCH_WOOD || log == Material.STRIPPED_BIRCH_LOG || log == Material.STRIPPED_BIRCH_WOOD);
    }

    /**
     * 不可变的整数坐标，用于表示方块位置及计算偏移。
     */
    record Pos(int x, int y, int z) {
        Pos {
        }

        /**
         * 返回沿给定方向（六向之一）偏移一格后的新坐标。
         */
        Pos offset(int[] d) {
            return new Pos(this.x + d[0], this.y + d[1], this.z + d[2]);
        }
    }

    /**
     * 广度优先搜索队列中的节点：记录位置与距离起始点的深度。
     */
    private record Node(Pos pos, int depth) {
        private Node {
        }
    }

    /**
     * 搜索阶段枚举，决定当前节点应如何被解释与扩展。
     */
    private enum Phase {
        FIND_LOG,
        LOGS,
        LEAVES,
        DONE,
        ABORTED;

        Phase() {
        }

        // $FF: synthetic method
        private static Phase[] $values() {
            return new Phase[]{FIND_LOG, LOGS, LEAVES, DONE, ABORTED};
        }
    }
}
