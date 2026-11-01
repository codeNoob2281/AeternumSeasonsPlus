package Kinkin.aeternum.farming;


import java.util.function.UnaryOperator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class VillagerCropInventory {
    VillagerCropInventory() {
        super();
    }

    static boolean normalize(Inventory inventory, UnaryOperator<ItemStack> canonicalize) {
        ItemStack[] original = inventory.getContents();
        ItemStack[] result = original.clone();
        boolean[] eligible = new boolean[result.length];
        boolean changed = false;

        for(int slot = 0; slot < result.length; ++slot) {
            ItemStack input = result[slot];
            if (input != null && !input.getType().isAir()) {
                ItemStack canonical = (ItemStack)canonicalize.apply(input);
                if (canonical != null) {
                    result[slot] = canonical;
                    eligible[slot] = true;
                    changed |= !canonical.equals(input);

                    for(int previous = 0; previous < slot && canonical.getAmount() > 0; ++previous) {
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
