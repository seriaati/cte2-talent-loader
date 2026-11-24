package moe.seria.cte2talents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

// Opens each container in reach the way a click would, one at a time, and closes it once its
// contents have arrived. GearExport records it on close, as it does for containers opened by hand.
final class GearScan {
    // Matched by name so neither mod is needed to compile or run.
    private static final Set<String> STORAGE_CLASSES = Set.of(
            "net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity",
            "com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity");
    // A container that hasn't opened within a second is skipped: locked, claimed by someone else, or blocked.
    private static final int OPEN_TIMEOUT_TICKS = 20;
    // Contents follow the open packet; half a second leaves room for lag and a large Refined Storage network.
    private static final int SETTLE_TICKS = 10;

    private static final Deque<BlockPos> queue = new ArrayDeque<>();
    private static BlockPos current;
    private static boolean opened;
    private static int wait;
    private static int recorded;
    private static int skipped;

    static int start(CommandSourceStack source) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!player.getMainHandItem().isEmpty()) {
            source.sendFailure(Component.literal("Switch to an empty hotbar slot first. The scan clicks each container with your main hand, and an item in it could get used on the block."));
            return 0;
        }
        Level level = player.level();
        queue.clear();
        Set<Object> seen = new HashSet<>();
        int r = (int) Math.ceil(player.getBlockReach()) + 1;
        for (BlockPos p : BlockPos.betweenClosed(player.blockPosition().offset(-r, -r, -r), player.blockPosition().offset(r, r, r))) {
            // The server allows reach plus 1.5 blocks; 1.0 keeps clear of that edge.
            if (!player.canReach(p, 1.0)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (!isStorage(be)) continue;
            BlockPos pos = p.immutable();
            Object key = be instanceof EnderChestBlockEntity ? "ender chest" : GearExport.containerPos(level, pos);
            if (seen.add(key)) queue.add(pos);
        }
        if (queue.isEmpty()) {
            source.sendFailure(Component.literal("No chests, barrels, Sophisticated Storage blocks or Refined Storage grids in reach. Stand closer to them."));
            return 0;
        }
        recorded = 0;
        skipped = 0;
        source.sendSuccess(() -> Component.literal("Scanning " + queue.size() + " containers in reach. Stay put until it's done."), false);
        return 1;
    }

    private static boolean isStorage(BlockEntity be) {
        if (be instanceof RandomizableContainerBlockEntity || be instanceof EnderChestBlockEntity) return true;
        for (Class<?> c = be == null ? null : be.getClass(); c != null; c = c.getSuperclass()) {
            if (STORAGE_CLASSES.contains(c.getName())) return true;
        }
        return false;
    }

    static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (current == null && queue.isEmpty())) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            stop();
            return;
        }

        if (current == null) {
            // Waits for the chat screen, or anything the player opened, to close first.
            if (mc.screen != null) return;
            if (!player.getMainHandItem().isEmpty()) {
                player.displayClientMessage(Component.literal("cte2gear: scan stopped, your main hand isn't empty."), false);
                stop();
                return;
            }
            current = queue.poll();
            GearExport.scanning = current;
            opened = false;
            wait = OPEN_TIMEOUT_TICKS;
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(current), Direction.UP, current, false));
            return;
        }

        if (!opened && mc.screen instanceof AbstractContainerScreen) {
            opened = true;
            wait = SETTLE_TICKS;
        }
        if (--wait > 0) return;
        if (opened) {
            recorded++;
            if (mc.screen instanceof AbstractContainerScreen) player.closeContainer();
        } else {
            skipped++;
        }
        GearExport.scanning = null;
        current = null;
        if (queue.isEmpty()) {
            String msg = "cte2gear: scan done, opened " + recorded + " containers";
            if (skipped > 0) msg += ", " + skipped + " didn't open (locked, claimed, or blocked)";
            player.displayClientMessage(Component.literal(msg + "."), false);
        }
    }

    private static void stop() {
        queue.clear();
        current = null;
        GearExport.scanning = null;
    }
}
