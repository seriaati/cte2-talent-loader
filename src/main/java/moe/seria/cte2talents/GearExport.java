package moe.seria.cte2talents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.robertx22.mine_and_slash.a_libraries.curios.MyCuriosUtils;
import com.robertx22.mine_and_slash.capability.player.PlayerData;
import com.robertx22.mine_and_slash.capability.player.container.BackpackScreen;
import com.robertx22.mine_and_slash.capability.player.helper.BackpackInventory;
import com.robertx22.mine_and_slash.capability.player.helper.MyInventory;
import com.robertx22.mine_and_slash.database.data.gear_types.bases.BaseGearType;
import com.robertx22.mine_and_slash.saveclasses.item_classes.GearItemData;
import com.robertx22.mine_and_slash.saveclasses.jewel.JewelItemData;
import com.robertx22.mine_and_slash.saveclasses.skill_gem.SkillGemData;
import com.robertx22.mine_and_slash.uncommon.datasaving.Load;
import com.robertx22.mine_and_slash.uncommon.datasaving.StackSaving;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// A client only sees what is inside a container while it is open, so each one is recorded when
// it closes and kept in the file until it is opened again or cleared.
final class GearExport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> PLAYER_LOCATIONS = List.of("equipped", "inventory", "socketed jewels", "gem slots");
    private static final boolean REFINED_STORAGE = ModList.get().isLoaded("refinedstorage");

    // Building Mine and Slash tooltips is slow enough that a Refined Storage network of over a
    // thousand items stalls the game for a moment, so closed containers are described a few
    // milliseconds per tick instead of all at once.
    private static final long DESCRIBE_BUDGET_NANOS = 3_000_000;
    // One thread, so writes land in the order they were made.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cte2gear writer");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, List<JsonObject>> byLocation = new LinkedHashMap<>();
    private static final Map<String, Pending> pending = new LinkedHashMap<>();
    private static Path loadedFrom;

    private static final class Pending {
        final Iterator<ItemStack> left;
        final List<JsonObject> done = new ArrayList<>();

        Pending(List<ItemStack> stacks) {
            left = stacks.iterator();
        }
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cte2gear")
                .executes(ctx -> export(ctx.getSource()))
                .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource()))));
    }

    static void onClose(ScreenEvent.Closing event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || screen instanceof EffectRenderingInventoryScreen) return;

        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            // The player's own items, and Mine and Slash's jewel and gem slots, are exported on their own.
            // The Master Bag is a MyInventory too, but its contents only reach the client while it's open.
            if (slot.container instanceof Inventory) continue;
            if (!(slot.container instanceof MyInventory) || slot.container instanceof BackpackInventory) stacks.add(slot.getItem());
        }
        if (REFINED_STORAGE) stacks.addAll(RefinedStorageGrid.items(screen));

        // Nothing changes these stacks once their screen is gone, so they're read later without copying.
        pending.put(location(mc, screen), new Pending(stacks));
    }

    static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            pending.clear();
            return;
        }
        long deadline = System.nanoTime() + DESCRIBE_BUDGET_NANOS;
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            Map.Entry<String, Pending> next = pending.entrySet().iterator().next();
            String where = next.getKey();
            Pending job = next.getValue();
            while (job.left.hasNext() && System.nanoTime() < deadline) {
                JsonObject item = describe(player, where, job.left.next());
                if (item != null) job.done.add(item);
            }
            if (job.left.hasNext()) return;
            pending.remove(where);
            load();
            if (job.done.isEmpty() && !byLocation.containsKey(where)) continue;
            if (job.done.isEmpty()) byLocation.remove(where);
            else byLocation.put(where, job.done);
            write(player);
            player.displayClientMessage(Component.literal("cte2gear: recorded " + job.done.size() + " items from " + where), true);
        }
    }

    private static int export(CommandSourceStack source) {
        Player player = Minecraft.getInstance().player;
        load();
        int count = write(player);
        source.sendSuccess(() -> Component.literal("Wrote " + count + " items to " + file(".md") + " and " + file(".json").getFileName()), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        load();
        byLocation.clear();
        pending.clear();
        write(Minecraft.getInstance().player);
        source.sendSuccess(() -> Component.literal("Forgot every recorded container. Open them again to record them."), false);
        return 1;
    }

    // Containers are told apart by the block they were opened from, so opening one again replaces
    // its record. Screens opened without a block, like a backpack, go by their title alone.
    private static String location(Minecraft mc, AbstractContainerScreen<?> screen) {
        // Master Bag tabs have no title, but each has its own menu type, like backpack_gears.
        if (screen instanceof BackpackScreen) {
            return "master bag, " + ForgeRegistries.MENU_TYPES.getKey(screen.getMenu().getType()).getPath().replace("backpack_", "") + " tab";
        }
        String title = screen.getTitle().getString();
        BlockPos pos = lookedAt(mc);
        // Every ender chest opens the same inventory.
        if (pos == null || mc.level.getBlockState(pos).getBlock() instanceof EnderChestBlock) return title;
        pos = containerPos(mc.level, pos);
        return title + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (" + mc.level.dimension().location().getPath() + ")";
    }

    private static BlockPos lookedAt(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockEntity(hit.getBlockPos()) != null ? hit.getBlockPos() : null;
    }

    // Either half of a double chest opens the same inventory, so both go by the lower position.
    static BlockPos containerPos(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (other.compareTo(pos) < 0) return other;
        }
        return pos;
    }

    private static List<ItemStack> contents(Container container) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) stacks.add(container.getItem(i));
        return stacks;
    }

    private static List<JsonObject> describe(Player player, String location, List<ItemStack> stacks) {
        List<JsonObject> items = new ArrayList<>();
        for (ItemStack stack : stacks) {
            JsonObject item = describe(player, location, stack);
            if (item != null) items.add(item);
        }
        return items;
    }

    // Null for anything that isn't Mine and Slash gear, a jewel or a skill gem.
    private static JsonObject describe(Player player, String location, ItemStack stack) {
        JsonObject item = new JsonObject();
        if (StackSaving.GEARS.has(stack)) {
            GearItemData gear = StackSaving.GEARS.loadFrom(stack);
            BaseGearType type = gear.GetBaseGearType();
            item.addProperty("kind", "gear");
            item.addProperty("slot", type != null ? type.gear_slot : "unknown");
            item.addProperty("rarity", gear.getRarityId());
            item.addProperty("level", gear.getLevel());
        } else if (StackSaving.JEWEL.has(stack)) {
            JewelItemData jewel = StackSaving.JEWEL.loadFrom(stack);
            item.addProperty("kind", "jewel");
            item.addProperty("rarity", jewel.getRarityId());
            item.addProperty("level", jewel.getLevel());
        } else if (StackSaving.SKILL_GEM.has(stack)) {
            SkillGemData gem = StackSaving.SKILL_GEM.loadFrom(stack);
            item.addProperty("kind", gem.type.name().toLowerCase(Locale.ROOT) + " gem");
            item.addProperty("rarity", gem.getRarityId());
        } else {
            return null;
        }
        item.addProperty("name", stack.getHoverName().getString());
        item.addProperty("location", location);
        JsonArray lines = new JsonArray();
        // Skips the first line, the item's name. Mine and Slash adds its stat lines to the
        // normal tooltip, so this is the text a player sees when hovering without Shift.
        stack.getTooltipLines(player, TooltipFlag.Default.NORMAL).stream().skip(1)
                .map(c -> ChatFormatting.stripFormatting(c.getString()).strip())
                .filter(s -> !s.isEmpty())
                .forEach(lines::add);
        item.add("tooltip", lines);
        return item;
    }

    private static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve("cte2gear");
    }

    private static Path file(String extension) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        String name = server != null ? server.name : "singleplayer";
        return folder().resolve(name.replaceAll("[^A-Za-z0-9._-]+", "_") + extension);
    }

    // Containers recorded in an earlier session come back from this server's file.
    private static void load() {
        Path json = file(".json");
        if (json.equals(loadedFrom)) return;
        loadedFrom = json;
        byLocation.clear();
        try {
            if (!Files.exists(json)) return;
            JsonArray items = JsonParser.parseString(Files.readString(json)).getAsJsonObject().getAsJsonArray("items");
            for (JsonElement e : items) {
                JsonObject item = e.getAsJsonObject();
                String location = item.get("location").getAsString();
                if (!PLAYER_LOCATIONS.contains(location)) byLocation.computeIfAbsent(location, k -> new ArrayList<>()).add(item);
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {}, starting it over", json, e);
        }
    }

    private static int write(Player player) {
        PlayerData data = Load.player(player);
        Inventory inv = player.getInventory();
        List<ItemStack> equipped = new ArrayList<>(inv.armor);
        equipped.addAll(inv.offhand);
        equipped.add(inv.getSelected());
        equipped.addAll(MyCuriosUtils.getAllSlots(player));
        List<ItemStack> carried = new ArrayList<>(inv.items);
        carried.remove(inv.selected);
        List<ItemStack> gems = contents(data.getSkillGemInventory().getGemsInv());
        gems.addAll(contents(data.getSkillGemInventory().getAuraInv()));

        Map<String, List<JsonObject>> all = new LinkedHashMap<>();
        all.put("equipped", describe(player, "equipped", equipped));
        all.put("inventory", describe(player, "inventory", carried));
        all.put("socketed jewels", describe(player, "socketed jewels", contents(data.getJewels().jewelInventory)));
        all.put("gem slots", describe(player, "gem slots", gems));
        all.putAll(byLocation);
        List<JsonObject> items = all.values().stream().flatMap(List::stream).toList();

        int level = Load.Unit(player).getLevel();
        JsonObject root = new JsonObject();
        root.addProperty("format", "cte2-gear");
        root.addProperty("player", player.getName().getString());
        root.addProperty("level", level);
        JsonArray array = new JsonArray();
        items.forEach(array::add);
        root.add("items", array);

        // The items are never changed after they're described, so the writer can read them unlocked.
        String name = player.getName().getString();
        Path json = file(".json");
        Path md = file(".md");
        WRITER.execute(() -> {
            try {
                Files.createDirectories(folder());
                Files.writeString(json, GSON.toJson(root));
                Files.writeString(md, markdown(name, level, items));
            } catch (IOException e) {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.player != null) mc.player.displayClientMessage(Component.literal("cte2gear: couldn't write " + folder() + ": " + e.getMessage()), false);
                });
            }
        });
        return items.size();
    }

    private static String markdown(String player, int level, List<JsonObject> items) {
        StringBuilder md = new StringBuilder();
        md.append("# Gear owned by ").append(player).append(", level ").append(level).append("\n\n");
        md.append("Written ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(". Items in storage are as they were when that container was last opened.\n");

        Map<String, List<JsonObject>> groups = new TreeMap<>();
        for (JsonObject item : items) {
            String group = item.has("slot") ? item.get("slot").getAsString() : item.get("kind").getAsString();
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(item);
        }
        groups.forEach((group, list) -> {
            md.append("\n## ").append(group).append(" (").append(list.size()).append(")\n");
            list.stream().sorted(Comparator.comparing(i -> !i.get("location").getAsString().equals("equipped"))).forEach(item -> {
                md.append("\n### ").append(item.get("name").getAsString()).append("\n\n");
                md.append("- ").append(item.get("rarity").getAsString());
                if (item.has("level")) md.append(", level ").append(item.get("level").getAsInt());
                md.append(", ").append(item.get("location").getAsString()).append("\n");
                item.getAsJsonArray("tooltip").forEach(line -> md.append("  - ").append(line.getAsString()).append("\n"));
            });
        });
        return md.toString();
    }
}
