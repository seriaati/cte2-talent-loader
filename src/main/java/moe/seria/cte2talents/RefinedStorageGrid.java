package moe.seria.cte2talents;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.stack.ItemGridStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// Kept apart from GearExport so Refined Storage's classes load only when the mod is installed.
final class RefinedStorageGrid {
    // Everything stored in the network, whatever the grid's search box hides. Refined Storage
    // sends each item's full NBT, so Mine and Slash stats come through. Craftable entries are
    // pattern outputs, not items that exist.
    static List<ItemStack> items(Screen screen) {
        if (!(screen instanceof GridScreen grid)) return List.of();
        return grid.getView().getAllStacks().stream()
                .filter(s -> s instanceof ItemGridStack && !s.isCraftable())
                .map(s -> ((ItemGridStack) s).getStack())
                .toList();
    }
}
