package moe.seria.cte2talents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.robertx22.mine_and_slash.a_libraries.curios.MyCuriosUtils;
import com.robertx22.mine_and_slash.capability.player.PlayerData;
import com.robertx22.mine_and_slash.capability.player.helper.GemInventoryHelper;
import com.robertx22.mine_and_slash.capability.player.helper.SocketedGem;
import com.robertx22.mine_and_slash.database.data.chaos_stats.ChaosStat;
import com.robertx22.mine_and_slash.database.data.game_balance_config.PlayerPointsConfig;
import com.robertx22.mine_and_slash.database.data.game_balance_config.PlayerPointsType;
import com.robertx22.mine_and_slash.database.data.gear_types.bases.BaseGearType;
import com.robertx22.mine_and_slash.database.data.omen.OmenData;
import com.robertx22.mine_and_slash.database.data.rarities.GearRarityType;
import com.robertx22.mine_and_slash.database.data.spell_school.SpellSchool;
import com.robertx22.mine_and_slash.database.data.talent_tree.TalentTree;
import com.robertx22.mine_and_slash.database.data.unique_items.UniqueGear;
import com.robertx22.mine_and_slash.database.registry.ExileDB;
import com.robertx22.mine_and_slash.itemstack.ExileStack;
import com.robertx22.mine_and_slash.saveclasses.PointData;
import com.robertx22.mine_and_slash.saveclasses.gearitem.gear_parts.AffixData;
import com.robertx22.mine_and_slash.saveclasses.gearitem.gear_parts.GearSocketsData;
import com.robertx22.mine_and_slash.saveclasses.gearitem.gear_parts.SocketData;
import com.robertx22.mine_and_slash.saveclasses.item_classes.GearItemData;
import com.robertx22.mine_and_slash.saveclasses.jewel.CraftedUniqueJewelData;
import com.robertx22.mine_and_slash.saveclasses.jewel.JewelItemData;
import com.robertx22.mine_and_slash.saveclasses.spells.SpellSchoolsData;
import com.robertx22.mine_and_slash.uncommon.datasaving.Load;
import com.robertx22.mine_and_slash.uncommon.datasaving.StackSaving;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Writes the character as it stands in cte2-db's build file format (exportBuild in its
// src/lib/buildRules.ts), so the editor can import it. Settings that only exist in the editor's
// calculator, like the enemy and the aura taken to be on, are left for it to default.
final class BuildExport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    // The editor's pools, and the game's.
    private static final Map<String, PlayerPointsType> POOLS = Map.of(
            "talents", PlayerPointsType.TALENTS, "ascendancy", PlayerPointsType.ASCENDANCY, "atlas", PlayerPointsType.ATLAS,
            "spells", PlayerPointsType.SPELLS, "passives", PlayerPointsType.PASSIVES, "stats", PlayerPointsType.STATS);
    // GemInventoryHelper's hotbar, as cte2-db's HOTBAR_SLOTS.
    private static final int HOTBAR_SLOTS = 8;
    // Better Compatibility Checker's config is where Craft to Exile 2 keeps its own version.
    private static final Pattern PACK_VERSION = Pattern.compile("modpackVersion\\s*=\\s*\"([^\"]+)\"");

    static int export(CommandSourceStack source, String name) {
        Player player = Minecraft.getInstance().player;
        String file = name != null ? name : player.getName().getString() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path path = BuildCommand.folder().resolve(file.replaceAll("[^A-Za-z0-9._ -]+", "_") + ".json");
        if (Files.exists(path)) {
            source.sendFailure(Component.literal(path.getFileName() + " already exists. Pick another name."));
            return 0;
        }

        JsonObject root = new JsonObject();
        root.addProperty("format", "cte2-build");
        root.addProperty("version", 1);
        String pack = packVersion();
        if (pack != null) root.addProperty("pack", pack);
        root.add("build", build(player, name != null ? name : player.getName().getString()));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (IOException e) {
            source.sendFailure(Component.literal("Couldn't write " + path + ": " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Wrote " + path + ". Import it at cte2.seria.moe/build under Builds."), false);
        return 1;
    }

    private static String packVersion() {
        try {
            Matcher m = PACK_VERSION.matcher(Files.readString(FMLPaths.CONFIGDIR.get().resolve("bcc-common.toml")));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject build(Player player, String name) {
        PlayerData data = Load.player(player);
        JsonObject build = new JsonObject();
        // cte2-db cuts names to 60 characters.
        build.addProperty("name", name.length() > 60 ? name.substring(0, 60) : name);
        build.addProperty("level", Load.Unit(player).getLevel());

        JsonObject bonus = new JsonObject();
        POOLS.forEach((pool, type) -> {
            // PlayerPointsType.getBonusPoints, which is private; cheat points given by an admin aren't counted.
            PlayerPointsConfig config = type.getConfig();
            int points = data.points.get(type).getBonusPoints() + (type == PlayerPointsType.TALENTS ? data.bonusTalents : 0);
            bonus.addProperty(pool, Math.min(points, config.max_bonus_points));
        });
        build.add("bonus", bonus);

        JsonObject trees = new JsonObject();
        BuildCommand.TREES.forEach((fileTree, gameTree) -> {
            TalentTree tree = ExileDB.TalentTrees().get(gameTree);
            List<PointData> nodes = BuildCommand.nodes(tree);
            JsonArray taken = new JsonArray();
            for (int i = 0; i < nodes.size(); i++) {
                if (data.talents.isAllocated(tree, nodes.get(i))) taken.add(i);
            }
            trees.add(fileTree, taken);
        });
        build.add("trees", trees);

        JsonObject stats = new JsonObject();
        data.statPoints.map.forEach((id, n) -> {
            if (n > 0) stats.addProperty(id, n);
        });
        build.add("stats", stats);

        SpellSchoolsData learned = data.ascClass;
        JsonArray classes = new JsonArray();
        JsonObject perks = new JsonObject();
        for (String id : learned.school()) {
            if (!ExileDB.SpellSchools().isRegistered(id)) continue;
            classes.add(id);
            SpellSchool school = ExileDB.SpellSchools().get(id);
            for (String perk : school.perks.keySet()) {
                int level = learned.getLevel(perk);
                if (level > 0) perks.addProperty(perk, level);
            }
        }
        build.add("classes", classes);
        build.add("perks", perks);

        GemInventoryHelper gems = data.getSkillGemInventory();
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            SocketedGem gem = gems.getHotbarGem(i);
            if (gem == null || gem.spell == null || gem.spell.id == null || gem.spell.id.isEmpty()) {
                hotbar.add((JsonObject) null);
                continue;
            }
            JsonObject skill = new JsonObject();
            skill.addProperty("spell", gem.spell.id);
            JsonArray supports = new JsonArray();
            gem.getSupportDatas().forEach(s -> supports.add(rolled(s.id, s.perc)));
            skill.add("supports", supports);
            hotbar.add(skill);
        }
        build.add("hotbar", hotbar);

        JsonObject gear = new JsonObject();
        JsonObject codex = null;
        List<ItemStack> worn = new ArrayList<>(MyCuriosUtils.getAllSlots(player));
        worn.add(player.getItemBySlot(EquipmentSlot.HEAD));
        worn.add(player.getItemBySlot(EquipmentSlot.CHEST));
        worn.add(player.getItemBySlot(EquipmentSlot.LEGS));
        worn.add(player.getItemBySlot(EquipmentSlot.FEET));
        if (StackSaving.GEARS.has(player.getMainHandItem())) gear.add("weapon", item(player.getMainHandItem()));
        if (StackSaving.GEARS.has(player.getOffhandItem())) gear.add("offhand", item(player.getOffhandItem()));
        for (ItemStack stack : worn) {
            if (StackSaving.OMEN.has(stack)) codex = codex(StackSaving.OMEN.loadFrom(stack));
            if (!StackSaving.GEARS.has(stack)) continue;
            BaseGearType type = StackSaving.GEARS.loadFrom(stack).GetBaseGearType();
            if (type == null) continue;
            String slot = type.gear_slot.equals("ring") ? (gear.has("ring1") ? "ring2" : "ring1") : type.gear_slot;
            if (!gear.has(slot)) gear.add(slot, item(stack));
        }
        build.add("gear", gear);
        build.add("codex", codex);

        JsonArray augments = new JsonArray();
        gems.getAurasGems().forEach(a -> augments.add(rolled(a.id, a.perc)));
        build.add("augments", augments);

        JsonArray jewels = new JsonArray();
        data.getJewels().getAllJewels().forEach(j -> jewels.add(jewel(j)));
        build.add("jewels", jewels);
        return build;
    }

    private static JsonObject rolled(String id, Integer roll) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (roll != null) o.addProperty("roll", roll);
        return o;
    }

    private static JsonArray affixes(List<AffixData> list) {
        JsonArray out = new JsonArray();
        if (list != null) list.forEach(a -> out.add(rolled(a.id, a.p)));
        return out;
    }

    // cte2-db's Item: each part's id and roll as GearItemData holds them.
    private static JsonObject item(ItemStack stack) {
        GearItemData gear = StackSaving.GEARS.loadFrom(stack);
        ExileStack exile = ExileStack.of(stack);
        JsonObject o = new JsonObject();
        o.addProperty("base", gear.gtype);
        UniqueGear unique = gear.uniqueStats != null ? gear.uniqueStats.getUnique(exile) : null;
        if (unique != null) o.addProperty("unique", unique.GUID());
        o.addProperty("rarity", gear.rar);
        o.addProperty("level", gear.lvl);
        if (gear.baseStats != null && gear.baseStats.p != null) o.addProperty("baseRoll", gear.baseStats.p);
        int quality = gear.getQualityBaseStatsBonus(exile);
        if (quality > 0) o.addProperty("quality", quality);
        if (gear.imp != null && gear.imp.imp != null && !gear.imp.imp.isEmpty()) o.add("implicit", rolled(gear.imp.imp, gear.imp.p));
        o.add("prefix", affixes(gear.affixes != null ? gear.affixes.pre : null));
        o.add("suffix", affixes(gear.affixes != null ? gear.affixes.suf : null));
        if (unique != null && gear.uniqueStats.perc != null) {
            JsonArray rolls = new JsonArray();
            gear.uniqueStats.perc.forEach(rolls::add);
            o.add("uniqueRolls", rolls);
        }

        JsonArray sockets = new JsonArray();
        if (gear.sockets != null) {
            for (SocketData s : gear.sockets.getSocketed()) {
                if (s.isEmpty() || !(s.isGem() || s.isRune())) continue;
                JsonObject socket = rolled(s.g, s.p);
                socket.addProperty("kind", s.isGem() ? "gem" : "rune");
                sockets.add(socket);
            }
            if (gear.sockets.hasRuneWord()) {
                Integer roll = runewordRoll(gear.sockets);
                if (roll != null) o.addProperty("runewordRoll", roll);
            }
        }
        o.add("sockets", sockets);

        if (gear.ench != null && gear.ench.en != null && !gear.ench.en.isEmpty()) {
            JsonObject infusion = new JsonObject();
            infusion.addProperty("id", gear.ench.en);
            infusion.addProperty("rarity", gear.ench.rar);
            o.add("infusion", infusion);
        }
        if (exile.isCorrupted()) {
            String kind = corruptionKind(gear);
            if (kind != null) {
                JsonObject corruption = new JsonObject();
                corruption.addProperty("kind", kind);
                corruption.add("affixes", affixes(gear.affixes != null ? gear.affixes.cor : null));
                o.add("corruption", corruption);
            }
        }

        JsonArray enchants = new JsonArray();
        EnchantmentHelper.getEnchantments(stack).forEach((enchant, level) -> {
            JsonObject e = new JsonObject();
            e.addProperty("id", String.valueOf(ForgeRegistries.ENCHANTMENTS.getKey(enchant)));
            e.addProperty("level", level);
            enchants.add(e);
        });
        o.add("enchants", enchants);
        return o;
    }

    // GearSocketsData keeps the runeword's roll in a private field with no getter.
    private static Integer runewordRoll(GearSocketsData sockets) {
        try {
            Field rp = GearSocketsData.class.getDeclaredField("rp");
            rp.setAccessible(true);
            return rp.getInt(sockets);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // The game doesn't keep which ChaosStat corrupted an item, only what it did: how many affixes
    // it added, and whether it added a socket past what the rarity allows. Those tell the kinds apart.
    private static String corruptionKind(GearItemData gear) {
        int corrupted = gear.affixes != null && gear.affixes.cor != null ? gear.affixes.cor.size() : 0;
        int extraSockets = gear.sockets != null && gear.sockets.getTotalSockets() > gear.getRarity().sockets.max ? 1 : 0;
        for (ChaosStat kind : ExileDB.ChaosStats().getList()) {
            if (kind.for_item_rarities.contains(gear.rar) && kind.affix_number == corrupted && kind.bonus_sockets == extraSockets) return kind.id;
        }
        return null;
    }

    private static JsonObject codex(OmenData omen) {
        JsonObject o = new JsonObject();
        o.addProperty("id", omen.id);
        o.addProperty("rarity", omen.rar);
        o.addProperty("level", omen.lvl);
        JsonObject pieces = new JsonObject();
        for (GearRarityType type : GearRarityType.values()) {
            pieces.addProperty(type.name().toLowerCase(Locale.ROOT), omen.rarities != null ? omen.rarities.getOrDefault(type, 0) : 0);
        }
        o.add("pieces", pieces);
        JsonArray slots = new JsonArray();
        if (omen.slot_req != null) {
            omen.slot_req.forEach(req -> {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", req.slot);
                slot.addProperty("type", req.rtype.name().toLowerCase(Locale.ROOT));
                slots.add(slot);
            });
        }
        o.add("slots", slots);
        JsonArray affixes = new JsonArray();
        if (omen.aff != null) omen.aff.forEach(a -> affixes.add(a.id));
        o.add("affixes", affixes);
        return o;
    }

    private static JsonObject jewel(JewelItemData jewel) {
        JsonObject o = new JsonObject();
        String kind = jewel.style;
        if (jewel.uniq != null && CraftedUniqueJewelData.CRAFTED_UNIQUE_ID.equals(jewel.uniq.id)) kind = "crafted";
        if (jewel.uniq != null && CraftedUniqueJewelData.WATCHER_EYE.equals(jewel.uniq.id)) kind = "eye";
        o.addProperty("kind", kind);
        o.addProperty("rarity", jewel.rar);
        o.addProperty("level", jewel.lvl);
        o.add("affixes", affixes(jewel.affixes));
        o.add("corruption", affixes(jewel.cor));
        return o;
    }
}
