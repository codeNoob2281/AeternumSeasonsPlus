package kinkin.aeternum.farming;


import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.UnaryOperator;

/**
 * 村民作物库存整理工具。
 *
 * <p>遍历村民背包中的物品，用给定的“规范化”函数（{@link UnaryOperator}）将每个物品转换为
 * 规范形式，并将可以被合并的同类堆叠合并到靠前的槽位中。整个过程不改变无法识别的物品。</p>
 */
final class VillagerCropInventory {
    VillagerCropInventory() {
        super();
    }

    /**
     * 规范化并合并库存中的物品。
     *
     * @param inventory    目标库存
     * @param canonicalize 将单个物品转换为规范形式；返回 null 表示保留原物品
     * @return 若库存内容发生改变则返回 true
     */
    static boolean normalize(Inventory inventory, UnaryOperator<ItemStack> canonicalize) {
        ItemStack[] original = inventory.getContents();
        ItemStack[] result = original.clone();
        boolean[] eligible = new boolean[result.length];
        boolean changed = false;

        for (int slot = 0; slot < result.length; ++slot) {
            ItemStack input = result[slot];
            if (input != null && !input.getType().isAir()) {
                ItemStack canonical = (ItemStack) canonicalize.apply(input);
                if (canonical != null) {
                    result[slot] = canonical;
                    eligible[slot] = true;
                    changed |= !canonical.equals(input);

                    for (int previous = 0; previous < slot && canonical.getAmount() > 0; ++previous) {
                        ItemStack target = result[previous];
                        if (eligible[previous] && target != null && target.isSimilar(canonical)) {
                            int room = Math.min(inventory.getMaxStackSize(), target.getMaxStackSize()) - target.getAmount();
                            int transfer = Math.min(Math.max(0, room), canonical.getAmount());
                            if (transfer != 0) {
                                target.setAmount(target.getAmount() + transfer);
                                canonical.setAmount(canonical.getAmount() - transfer);
                                changed = true;
                            }
                        }
                    }

                    if (canonical.getAmount() == 0) {
                        result[slot] = null;
                    }
                }
            }
        }

        if (changed) {
            inventory.setContents(result);
        }

        return changed;
    }
}
