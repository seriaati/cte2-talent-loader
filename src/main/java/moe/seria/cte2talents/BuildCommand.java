package moe.seria.cte2talents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.robertx22.library_of_exile.main.Packets;
import com.robertx22.mine_and_slash.capability.player.PlayerData;
import com.robertx22.mine_and_slash.database.data.game_balance_config.PlayerPointsType;
import com.robertx22.mine_and_slash.database.data.perks.Perk;
import com.robertx22.mine_and_slash.database.data.spell_school.SpellSchool;
import com.robertx22.mine_and_slash.database.data.talent_tree.TalentTree;
import com.robertx22.mine_and_slash.database.registry.ExileDB;
import com.robertx22.mine_and_slash.saveclasses.PointData;
import com.robertx22.mine_and_slash.saveclasses.perks.TalentsData;
import com.robertx22.mine_and_slash.saveclasses.spells.SpellSchoolsData;
import com.robertx22.mine_and_slash.uncommon.datasaving.Load;
import com.robertx22.mine_and_slash.vanilla_mc.packets.AllocateClassPointPacket;
import com.robertx22.mine_and_slash.vanilla_mc.packets.AllocateStatPacket;
import com.robertx22.mine_and_slash.vanilla_mc.packets.perks.PerkChangePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class BuildCommand {
    // The build file's tree names, and the game's.
    static final Map<String, String> TREES = new LinkedHashMap<>();
    static {
        TREES.put("talents", "talents");
        TREES.put("ascendancy", "ascendancy");
        TREES.put("atlas", "atlas_passives");
    }

    // Long enough for the server to answer every packet before the result is read back.
    private static final int CHECK_DELAY_TICKS = 40;

    private static int checkIn = -1;
    private static Runnable check;

    static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve("cte2builds");
    }

    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cte2build")
                .then(Commands.literal("export")
                        .executes(ctx -> BuildExport.export(ctx.getSource(), null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> BuildExport.export(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(files(), builder))
                        .executes(ctx -> load(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));
    }

    static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && checkIn >= 0 && --checkIn < 0) {
            check.run();
        }
    }

    private static List<String> files() {
        try {
            Files.createDirectories(folder());
            try (Stream<Path> list = Files.list(folder())) {
                return list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int load(CommandSourceStack source, String name) {
        JsonObject build;
        try {
            JsonObject file = JsonParser.parseString(Files.readString(folder().resolve(name))).getAsJsonObject();
            if (!"cte2-build".equals(file.has("format") ? file.get("format").getAsString() : null)) {
                source.sendFailure(Component.literal(name + " isn't a build exported from cte2-db."));
                return 0;
            }
            build = file.getAsJsonObject("build");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Couldn't read " + folder().resolve(name) + ": " + e.getMessage()));
            return 0;
        }

        List<Runnable> checks = new ArrayList<>();
        trees(build, source, checks);
        stats(build, source, checks);
        classes(build, source, checks);
        if (checks.isEmpty()) {
            source.sendFailure(Component.literal("Nothing to do: everything in " + name + " is already taken, or the build is empty."));
            return 0;
        }
        checkIn = CHECK_DELAY_TICKS;
        check = () -> checks.forEach(Runnable::run);
        return 1;
    }

    private static PlayerData data() {
        return Load.player(Minecraft.getInstance().player);
    }

    private static JsonObject object(JsonObject build, String key) {
        return build.has(key) && build.get(key).isJsonObject() ? build.getAsJsonObject(key) : new JsonObject();
    }

    private static void report(CommandSourceStack source, String section, int sent, int got) {
        if (got < sent) {
            source.sendFailure(Component.literal(section + ": the server turned down " + (sent - got) + " of " + sent + "."));
        } else {
            source.sendSuccess(() -> Component.literal(section + ": all " + sent + " in."), false);
        }
    }

    private static void trees(JsonObject build, CommandSourceStack source, List<Runnable> checks) {
        JsonObject trees = object(build, "trees");
        TalentsData talents = data().talents;

        TREES.forEach((fileTree, gameTree) -> {
            JsonArray wanted = trees.has(fileTree) ? trees.getAsJsonArray(fileTree) : new JsonArray();
            if (wanted.size() == 0) return;
            TalentTree tree = ExileDB.TalentTrees().get(gameTree);
            List<PointData> nodes = nodes(tree);

            Set<PointData> taken = new HashSet<>();
            for (PointData p : nodes) {
                if (talents.isAllocated(tree, p)) taken.add(p);
            }
            List<PointData> pending = new ArrayList<>();
            int unknown = 0;
            for (JsonElement e : wanted) {
                int i = e.getAsInt();
                if (i < 0 || i >= nodes.size() || tree.calcData.getPerk(nodes.get(i)) == null) {
                    unknown++;
                } else if (!taken.contains(nodes.get(i))) {
                    pending.add(nodes.get(i));
                }
            }

            // The server takes a node only if it is an entry or links to one already taken, so
            // send them outward from what is taken, each once its link is in.
            List<PointData> order = new ArrayList<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (var it = pending.iterator(); it.hasNext(); ) {
                    PointData p = it.next();
                    Set<PointData> links = tree.calcData.connections.getOrDefault(p, Set.of());
                    if (tree.calcData.getPerk(p).is_entry || links.stream().anyMatch(taken::contains)) {
                        order.add(p);
                        taken.add(p);
                        it.remove();
                        progress = true;
                    }
                }
            }

            for (PointData p : order) {
                Packets.sendToServer(new PerkChangePacket(tree, p, PerkChangePacket.ACTION.ALLOCATE));
            }

            String msg = fileTree + ": " + (wanted.size() - order.size() - pending.size() - unknown) + " already taken, sending " + order.size();
            if (!pending.isEmpty()) msg += ", " + pending.size() + " not linked to the rest";
            if (unknown > 0) msg += ", " + unknown + " not in this pack's tree (was the build made for another pack version?)";
            String line = msg;
            source.sendSuccess(() -> Component.literal(line), false);

            if (order.isEmpty()) return;
            checks.add(() -> {
                TalentsData now = data().talents;
                int got = (int) order.stream().filter(p -> now.isAllocated(tree, p)).count();
                report(source, fileTree, order.size(), got);
                if (got < order.size()) {
                    source.sendFailure(Component.literal("  (out of points, or a one-of-a-kind clash with a talent already taken)"));
                }
            });
        });
    }

    private static void stats(JsonObject build, CommandSourceStack source, List<Runnable> checks) {
        JsonObject wanted = object(build, "stats");
        if (wanted.size() == 0) return;
        Player player = Minecraft.getInstance().player;
        Map<String, Integer> have = data().statPoints.map;
        int free = PlayerPointsType.STATS.getFreePoints(player);

        Map<String, Integer> target = new LinkedHashMap<>();
        int sent = 0;
        int missing = 0;
        for (String id : wanted.keySet()) {
            if (!ExileDB.Stats().isRegistered(id)) continue;
            int start = have.getOrDefault(id, 0);
            int add = Math.max(0, wanted.get(id).getAsInt() - start);
            int can = Math.min(add, free);
            free -= can;
            missing += add - can;
            if (can == 0) continue;
            // The server takes at most 5 points a packet.
            for (int left = can; left > 0; left -= 5) {
                Packets.sendToServer(new AllocateStatPacket(ExileDB.Stats().get(id), AllocateStatPacket.ACTION.ALLOCATE, Math.min(5, left)));
            }
            target.put(id, start + can);
            sent += can;
        }

        String msg = "stats: sending " + sent + " points";
        if (missing > 0) msg += ", " + missing + " short of free points";
        String line = msg;
        source.sendSuccess(() -> Component.literal(line), false);

        if (target.isEmpty()) return;
        int total = sent;
        int before = target.entrySet().stream().mapToInt(e -> have.getOrDefault(e.getKey(), 0)).sum();
        checks.add(() -> {
            Map<String, Integer> now = data().statPoints.map;
            int got = target.keySet().stream().mapToInt(id -> now.getOrDefault(id, 0)).sum() - before;
            report(source, "stats", total, got);
        });
    }

    private static void classes(JsonObject build, CommandSourceStack source, List<Runnable> checks) {
        JsonObject wanted = object(build, "perks");
        JsonArray classes = build.has("classes") ? build.getAsJsonArray("classes") : new JsonArray();
        if (wanted.size() == 0) return;
        Player player = Minecraft.getInstance().player;
        SpellSchoolsData learned = data().ascClass;
        Set<String> schools = new HashSet<>(learned.school());
        Map<PlayerPointsType, Integer> free = new EnumMap<>(PlayerPointsType.class);

        Map<String, Integer> target = new LinkedHashMap<>();
        Set<String> placed = new HashSet<>();
        int sent = 0;
        int missing = 0;
        List<String> problems = new ArrayList<>();
        for (JsonElement c : classes) {
            String id = c.getAsString();
            if (!ExileDB.SpellSchools().isRegistered(id)) continue;
            SpellSchool school = ExileDB.SpellSchools().get(id);
            // Learning a class's first point picks the class, and the game allows two.
            if (!schools.contains(id) && schools.size() >= 2) {
                problems.add("you already have two other classes, so " + id + " can't be added");
                continue;
            }
            // Row by row, so lower-level rows come first.
            List<String> perks = school.perks.keySet().stream().filter(wanted::has)
                    .sorted(Comparator.comparingInt((String p) -> school.perks.get(p).y).thenComparingInt(p -> school.perks.get(p).x))
                    .toList();
            for (String perkId : perks) {
                placed.add(perkId);
                Perk perk = ExileDB.Perks().get(perkId);
                int start = learned.getLevel(perkId);
                int add = Math.max(0, Math.min(wanted.get(perkId).getAsInt(), perk.getMaxLevel()) - start);
                PlayerPointsType pool = perk.getPointType().getGeneralType();
                int poolFree = free.computeIfAbsent(pool, t -> t.getFreePoints(player));
                int can = 0;
                // Mirrors SpellSchoolsData.canLearn: each level opens at a character level.
                while (can < Math.min(add, poolFree) && school.isLevelEnoughFor(player, perk)
                        && school.isLevelEnoughForSpellLevelUp(player, perk, start + can)) {
                    can++;
                }
                free.put(pool, poolFree - can);
                missing += add - can;
                if (can == 0) continue;
                // The server takes at most 4 levels a packet.
                for (int left = can; left > 0; left -= 4) {
                    Packets.sendToServer(new AllocateClassPointPacket(school, perk, AllocateClassPointPacket.ACTION.ALLOCATE, Math.min(4, left)));
                }
                schools.add(id);
                target.put(perkId, start + can);
                sent += can;
            }
        }
        long stray = wanted.keySet().stream().filter(p -> !placed.contains(p)).count();
        if (stray > 0) problems.add(stray + " spells or passives aren't in the build's classes");

        String msg = "spells and passives: sending " + sent + " points";
        if (missing > 0) msg += ", " + missing + " held back (out of points, or your level is too low)";
        String line = msg;
        source.sendSuccess(() -> Component.literal(line), false);
        problems.forEach(p -> source.sendFailure(Component.literal("  " + p)));

        if (target.isEmpty()) return;
        int total = sent;
        Map<String, Integer> before = new HashMap<>();
        target.keySet().forEach(p -> before.put(p, learned.getLevel(p)));
        checks.add(() -> {
            SpellSchoolsData now = data().ascClass;
            int got = target.keySet().stream().mapToInt(p -> now.getLevel(p) - before.get(p)).sum();
            report(source, "spells and passives", total, got);
        });
    }

    // The tree's perks in the order cte2-db numbers them: row by row, each cell holding a perk id.
    // Grid columns and rows are what the game's PointData holds, as TalentGrid in Mine and Slash reads them.
    static List<PointData> nodes(TalentTree tree) {
        List<PointData> nodes = new ArrayList<>();
        String[] rows = tree.perks.split("\n");
        for (int y = 0; y < rows.length; y++) {
            String[] cells = rows[y].split(",");
            for (int x = 0; x < cells.length; x++) {
                if (ExileDB.Perks().isRegistered(cells[x].toLowerCase())) nodes.add(new PointData(x, y));
            }
        }
        return nodes;
    }
}
