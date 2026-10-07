import io.github.yudiiee.aicompanion.GameAI.human.*;
import java.lang.reflect.*;
public class HumanTest {
    static int fails = 0;
    static void check(boolean c, String msg) { if (!c) { fails++; System.out.println("FAIL: " + msg); } else System.out.println("ok: " + msg); }
    public static void main(String[] a) throws Exception {
        Method split = HumanChat.class.getDeclaredMethod("split", String.class); split.setAccessible(true);
        String h;
        h = HumanChat.humanize("Steve", "Steve: **Hey!** I found some `diamonds` 💎✨ over there!");
        System.out.println("  -> " + h); check("Hey! I found some diamonds over there!".equals(h), "strip prefix/markdown/emoji");
        check(HumanChat.humanize("Steve", "Steve is thinking...") == null, "thinking suppressed");
        check(HumanChat.humanize("Steve", "Steve is done thinking!") == null, "done thinking suppressed");
        h = HumanChat.humanize("Steve", "Running web search...."); System.out.println("  -> " + h); check(h != null && !h.toLowerCase().contains("web search"), "web search reworded");
        check(HumanChat.humanize("Steve", "[silent]") == null, "[silent] suppressed");
        h = HumanChat.humanize("Steve", "Established connection to openai's servers. Using gpt"); check(h != null && h.startsWith("\u0000SYSTEM:"), "connection line -> system notice");
        h = HumanChat.humanize("Steve", "§9Terminating all current tasks due to threat detections"); System.out.println("  -> " + h); check(h == null, "threat line hidden (no mob announcements)");
        {
            Method kind = PvpController.class.getDeclaredMethod("kindOf", Class.class); kind.setAccessible(true);
            check((int) kind.invoke(null, FakeMobs.Creeper.class) == 1, "creeper engaged on sight");
            check((int) kind.invoke(null, FakeMobs.Stray.class) == 2, "stray (skeleton subclass) engaged on sight");
            check((int) kind.invoke(null, FakeMobs.WitherSkeleton.class) == 0, "wither skeleton left for when it hits");
            check((int) kind.invoke(null, FakeMobs.Zombie.class) == 0, "zombie left alone until it hits");
        }
        h = HumanChat.humanize("Steve", "- step one\n- step two\n## Heading"); System.out.println("  -> " + h.replace("\n"," | ")); check(!h.contains("-") && !h.contains("#"), "bullets/headers removed");
        h = HumanChat.humanize("Steve", "\"quoted reply\""); check("quoted reply".equals(h), "wrapping quotes removed");
        h = HumanChat.humanize("Steve", "craft a diamond_pickaxe with 3 diamonds"); check(h.contains("diamond_pickaxe"), "underscores kept");
        @SuppressWarnings("unchecked") java.util.List<String> parts = (java.util.List<String>) split.invoke(null, "This is a sentence. ".repeat(30));
        System.out.println("  split -> " + parts.size() + " lines, max " + parts.stream().mapToInt(String::length).max().getAsInt());
        check(parts.size() <= 5 && parts.stream().allMatch(p -> p.length() <= 180), "split to chat lines");
        check(HumanChatListener.classifyLocal("follow me", "Steve") == HumanChatListener.Local.FOLLOW, "follow me");
        check(HumanChatListener.classifyLocal("Steve, stay here", "Steve") == HumanChatListener.Local.STAY, "stay here w/ name");
        check(HumanChatListener.classifyLocal("come here pls", "Steve") == HumanChatListener.Local.COME, "come here");
        check(HumanChatListener.classifyLocal("go explore", "Steve") == HumanChatListener.Local.WANDER, "go explore");
        check(HumanChatListener.classifyLocal("hey Steve!", "Steve") == HumanChatListener.Local.SMALL_TALK, "hey Steve");
        check(HumanChatListener.classifyLocal("ty", "Steve") == HumanChatListener.Local.SMALL_TALK, "ty");
        check(HumanChatListener.classifyLocal("can you mine some iron for me", "Steve") == HumanChatListener.Local.COLLECT, "iron task handled locally");
        check(HumanChatListener.classifyLocal("what do you think about building a castle", "Steve") == null, "conversation goes to LLM");
        check(HumanChatListener.classifyLocal("stay here and guard the base while i go mine", "Steve") == HumanChatListener.Local.STAY, "stay embedded");
        String[][] cases = {
            {"start mining","MINE"},{"go mine","MINE"},{"mine some stone","COLLECT"},{"get cobblestone","COLLECT"},{"that's mine","null"},
            {"get wood","COLLECT"},{"chop some trees","COLLECT"},{"can you get some logs","COLLECT"},{"chop","WOOD"},
            {"get iron","COLLECT"},{"find diamonds","COLLECT"},{"go mine coal","COLLECT"},{"look for ores","COLLECT"},
            {"mine 10 iron","COLLECT"},{"collect sand","COLLECT"},{"get me a stack of logs","COLLECT"},{"yo, dig up some dirt","COLLECT"},
            {"strip mine","STRIP"},{"can you strip mine at y -58","STRIP"},{"branch mine for diamonds","STRIP"},
            {"stop mining","STOP"},{"make me a chest","COLLECT"},{"craft a pickaxe","COLLECT"},{"make yourself a pickaxe","CRAFT"},{"i need 10 sticks","COLLECT"},{"fight me","PVP"},{"wanna 1v1?","PVP"},{"steve, pvp me","PVP"},{"hit me up later","null"},{"get ready","null"},{"get going","null"},{"get over here","COME"},
            {"craft","CRAFT"},{"make some torches","COLLECT"},
            {"what do you have","INVENTORY"},{"whats in your inventory","INVENTORY"},
            {"give me cobblestone","GIVE"},{"gimme the logs","GIVE"},{"can i have some wood","GIVE"},
            {"play","PLAY"},{"do something","PLAY"},{"help","PLAY"},
            {"stop","STOP"},{"stop moving","STAY"},{"stop following","STAY"},
            {"build a house","HOUSE"},{"can you build us a shelter","HOUSE"},{"lets make a base","HOUSE"},{"i'll build a house later","null"},
            {"store your stuff","STORE"},{"put your items in the chest","STORE"},{"empty your inventory","STORE"},{"dump everything","STORE"},
            {"use this chest","CHEST"},{"this chest is ours","CHEST"},
            {"get me 5 iron from the chest","TAKE"},{"grab some coal out of our chest","TAKE"},{"bring me the diamonds from storage","TAKE"},
            {"go home","HOME"},{"ok go back to base","HOME"},{"head home","HOME"},{"lets go home","HOME"},{"my home is far","null"},
            {"whats in the chest","CHESTS"},{"what do we have in the chests","CHESTS"},{"check the chest","CHESTS"},
            {"mine diamonds at y -58","STRIP"},
            {"hi","SMALL_TALK"},{"how do i make a nether portal?","null"}};
        for (String[] c : cases) { var r = HumanChatListener.classifyLocal(c[0], "steve"); check(String.valueOf(r).equals(c[1]), "'" + c[0] + "' -> " + r); }
        var t1 = Storage.parseTake("get me 5 iron from the chest"); check(t1 != null && t1.what().equals("iron") && t1.count() == 5, "take 5 iron -> " + t1);
        var t2 = Storage.parseTake("grab a stack of cobblestone from the chest"); check(t2 != null && t2.what().equals("cobblestone") && t2.count() == 64, "take stack -> " + t2);
        var t3 = Storage.parseTake("get the diamonds out of our chest"); check(t3 != null && t3.what().equals("diamonds") && t3.count() == 0, "take all diamonds -> " + t3);
        check(Storage.itemName("3 iron ingots").equals("iron ingot"), "itemName 3 iron ingots -> " + Storage.itemName("3 iron ingots"));
        check(Storage.matcherFor("iron ingots").test("iron_ingot") && !Storage.matcherFor("iron ingots").test("raw_iron"), "matcher iron ingot");
        check(Storage.matcherFor("wood").test("oak_log") && Storage.matcherFor("glass").test("glass"), "matcher wood/glass");
        var r1 = MiningSkills.parseCollect("get me a stack of logs"); check(r1 != null && r1.label().equals("collect 64 wood"), "stack of logs -> " + (r1 == null ? null : r1.label()));
        var r2 = MiningSkills.parseCollect("can you mine 10 iron ore please"); check(r2 != null && r2.label().equals("collect 10 iron"), "10 iron -> " + (r2 == null ? null : r2.label()));
        var r3 = MiningSkills.parseCollect("get diamonds"); check(r3 != null && r3.label().equals("collect 6 diamond"), "diamonds -> " + (r3 == null ? null : r3.label()));
        var r4 = MiningSkills.parseCollect("chop a tree"); check(r4 != null && r4.label().equals("collect 5 wood"), "a tree -> " + (r4 == null ? null : r4.label()));
        var d6 = MiningSkills.parseStrip("get back to mining diamonds at y level -58"); check(d6 != null && d6.label().equals("strip mine at y -58"), "diamonds at y -58 -> " + (d6 == null ? null : d6.label()));
        var d7 = MiningSkills.parseStrip("find diamonds at y -58"); check(d7 != null && d7.label().equals("strip mine at y -58"), "find diamonds at y -58 -> " + (d7 == null ? null : d7.label()));
        check(MiningSkills.parseStrip("get me 3 diamonds") == null, "plain diamonds isn't strip");
        var r5 = MiningSkills.parseStrip("strip mine for diamonds at y -50 40 blocks long"); check(r5 != null && r5.label().equals("strip mine at y -50"), "strip y -> " + (r5 == null ? null : r5.label()));
        var r6 = MiningSkills.parseStrip("strip mine for iron"); check(r6 != null && r6.label().equals("strip mine at y 16"), "strip for iron -> " + (r6 == null ? null : r6.label()));
        var rc = MiningSkills.parseStrip("strip mine for copper"); check(rc != null && rc.label().equals("strip mine at y 48"), "copper is not that deep -> " + (rc == null ? null : rc.label()));
        var r7 = MiningSkills.parseStrip("strip mine at y -80"); check(r7 != null && r7.label().equals("strip mine at y -59"), "clamped -> " + (r7 == null ? null : r7.label()));
        check(MiningSkills.resolve("cobblestone").matches().test("stone") && !MiningSkills.resolve("cobblestone").matches().test("cobblestone"), "cobble means natural stone");
        check(MiningSkills.resolve("wood").matches().test("oak_log") && !MiningSkills.resolve("wood").matches().test("stripped_oak_log") && !MiningSkills.resolve("wood").matches().test("oak_planks"), "wood = natural logs");
        check(MiningSkills.resolve("iron").matches().test("deepslate_iron_ore") && MiningSkills.resolve("iron").tier() == 2, "iron ore + deepslate, stone pick");
        check(MiningSkills.tierFor("obsidian") == 4 && MiningSkills.tierFor("dirt") == 0 && MiningSkills.tierFor("stone") == 1 && MiningSkills.tierFor("glowstone") == 0, "tiers");
        check(Protection.isManMade("oak_planks") && Protection.isManMade("cobblestone_wall") && !Protection.isManMade("bedrock") && !Protection.isManMade("stone"), "man-made blocks");
        var c1 = MiningSkills.parseCollect("make me a chest"); check(c1 != null && c1.label().equals("craft 1 chest"), "make me a chest -> " + (c1 == null ? null : c1.label()));
        var c2 = MiningSkills.parseCollect("i need 32 planks"); check(c2 != null && c2.label().equals("craft 32 planks"), "32 planks -> " + (c2 == null ? null : c2.label()));
        var c3 = MiningSkills.parseCollect("craft some torches"); check(c3 != null && c3.label().equals("craft 16 torches"), "torches -> " + (c3 == null ? null : c3.label()));
        var c4 = MiningSkills.parseCollect("can you make a stone pickaxe"); check(c4 != null && c4.label().equals("craft 1 stone pickaxe"), "stone pickaxe -> " + (c4 == null ? null : c4.label()));
        var c5 = MiningSkills.parseCollect("bring me 5 iron"); check(c5 != null && c5.label().equals("collect 5 iron"), "bring me 5 iron -> " + (c5 == null ? null : c5.label()));
        check(MiningSkills.parseCollect("i need help") == null && MiningSkills.parseCollect("make a house") == null, "not everything is a job");
        var s1 = MiningSkills.parseCollect("smelt 4 iron ore"); check(s1 != null && s1.label().equals("smelt 4 iron ingots"), "smelt 4 iron ore -> " + (s1 == null ? null : s1.label()));
        var s2 = MiningSkills.parseCollect("get me 5 iron ingots"); check(s2 != null && s2.label().equals("smelt 5 iron ingots"), "iron ingots -> " + (s2 == null ? null : s2.label()));
        var s3 = MiningSkills.parseCollect("can you smelt my iron"); check(s3 != null && s3.label().equals("smelt iron ingots"), "smelt my iron -> " + (s3 == null ? null : s3.label()));
        var s4 = MiningSkills.parseCollect("cook the beef"); check(s4 != null && s4.label().equals("smelt steak"), "cook beef -> " + (s4 == null ? null : s4.label()));
        var s5 = MiningSkills.parseCollect("collect 4 gold ingots"); check(s5 != null && s5.label().equals("smelt 4 gold ingots"), "gold ingots -> " + (s5 == null ? null : s5.label()));
        check("axe".equals(MiningSkills.toolKindFor(MiningSkills.resolve("wood"))), "wood -> axe");
        check("shovel".equals(MiningSkills.toolKindFor(MiningSkills.resolve("sand"))) && "shovel".equals(MiningSkills.toolKindFor(MiningSkills.resolve("dirt"))), "sand/dirt -> shovel");
        check(MiningSkills.toolKindFor(MiningSkills.resolve("iron")) == null, "ore -> pickaxe path");
        check(SurvivalBrain.toolTier("stone_axe") == 2 && SurvivalBrain.toolTier("wooden_shovel") == 1 && SurvivalBrain.toolTier("netherite_axe") == 5 && SurvivalBrain.toolTier("stick") == 0, "tool tiers");
        for (int i = 0; i < 3; i++) System.out.println("  hi -> " + HumanReactions.quickReply("Steve", "Udit", "hi"));
        System.out.println("  follow ack -> " + HumanReactions.followAck("Udit"));
        // ---- mine, farm, lighting ----
        {
            HumanChatListener.Local L;
            for (String t : new String[]{"dig down", "dig a staircase", "make a mine", "dig to bedrock", "dig down to y -40"}) {
                L = HumanChatListener.classifyLocal(t, "Steve"); check(L == HumanChatListener.Local.DIG, "'" + t + "' -> DIG (" + L + ")");
            }
            for (String t : new String[]{"lets go down to the beach", "head down the hill and get wood", "go to 58 70 -120", "go down there and mine some stone"}) {
                L = HumanChatListener.classifyLocal(t, "Steve"); check(L != HumanChatListener.Local.DIG, "'" + t + "' not dig-down (" + L + ")");
            }
            var coalReq = MiningSkills.parseStrip("strip mine for coal"); check(coalReq != null && coalReq.label().equals("strip mine for coal"), "coal label has no fixed y (" + (coalReq == null ? null : coalReq.label()) + ")");
            L = HumanChatListener.classifyLocal("mine diamonds at y -58", "Steve"); check(L == HumanChatListener.Local.STRIP, "ore at y still strip mining (" + L + ")");
            L = HumanChatListener.classifyLocal("dig for diamonds", "Steve"); check(L != HumanChatListener.Local.DIG, "'dig for diamonds' not dig-down (" + L + ")");
            L = HumanChatListener.classifyLocal("build a farm", "Steve"); check(L == HumanChatListener.Local.FARM, "build a farm -> FARM (" + L + ")");
            L = HumanChatListener.classifyLocal("harvest the crops", "Steve"); check(L == HumanChatListener.Local.FARM, "harvest the crops -> FARM (" + L + ")");
            L = HumanChatListener.classifyLocal("plant some seeds", "Steve"); check(L == HumanChatListener.Local.FARM, "plant some seeds -> FARM (" + L + ")");
            L = HumanChatListener.classifyLocal("harvest some wood", "Steve"); check(L != HumanChatListener.Local.FARM, "harvest some wood not farm (" + L + ")");
            L = HumanChatListener.classifyLocal("build a house", "Steve"); check(L == HumanChatListener.Local.HOUSE, "build a house still HOUSE (" + L + ")");

            Class<?> mh = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.MineHub");
            Method pd = mh.getDeclaredMethod("parseDigDown", String.class, Float.class); pd.setAccessible(true);
            Object r = pd.invoke(null, "dig down to y -40", null);
            check(r != null && ((MiningSkills.Request) r).label().equals("dig down to y -40"), "dig down to y -40 label");
            r = pd.invoke(null, "dig down", null);
            check(r != null && ((MiningSkills.Request) r).label().equals("dig down to y -58"), "dig down defaults to -58");
            Method mb = mh.getDeclaredMethod("isMountainBiome", String.class); mb.setAccessible(true);
            check((boolean) mb.invoke(null, "windswept_hills") && (boolean) mb.invoke(null, "jagged_peaks") && (boolean) mb.invoke(null, "cherry_grove")
                    && !(boolean) mb.invoke(null, "plains") && !(boolean) mb.invoke(null, "desert"), "mountain biomes for emeralds");
            Field bf = mh.getDeclaredField("BIOME"); bf.setAccessible(true);
            java.util.regex.Matcher bm = ((java.util.regex.Pattern) bf.get(null)).matcher(
                    "Reference{ResourceKey[minecraft:worldgen/biome / minecraft:stony_peaks]=net.minecraft.world.level.biome.Biome@1a2b}");
            check(bm.find() && bm.group(1).equals("stony_peaks"), "biome name read from holder text");

            check(MiningSkills.resolve("iron").stripY() == 16, "iron at y 16 (the ore index)");
            check(MiningSkills.resolve("gold").stripY() == -16, "gold at y -16");
            check(MiningSkills.resolve("lapis").stripY() == 0, "lapis at y 0");
            check(MiningSkills.resolve("copper").stripY() == 48, "copper at y 48");
            check(MiningSkills.resolve("diamonds").stripY() == -59 && MiningSkills.resolve("redstone").stripY() == -58, "diamond -59 / redstone -58 (the ore index)");
            check(MiningSkills.resolve("ancient debris").stripY() == 15, "ancient debris at 15");
            check(MiningSkills.resolve("emeralds").stripY() != null, "emeralds get strip mined (mountains)");

            Class<?> fm = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.Farm");
            Method age = fm.getDeclaredMethod("age", String.class); age.setAccessible(true);
            Method ripe = fm.getDeclaredMethod("ripe", String.class, int.class); ripe.setAccessible(true);
            check((int) age.invoke(null, "Block{minecraft:wheat}[age=7]") == 7, "crop age read");
            check((boolean) ripe.invoke(null, "wheat", 7) && !(boolean) ripe.invoke(null, "carrots", 6)
                    && (boolean) ripe.invoke(null, "beetroots", 3) && !(boolean) ripe.invoke(null, "stone", 7), "ripe crops");
            Method isSeed = fm.getDeclaredMethod("isSeed", String.class); isSeed.setAccessible(true);
            check((boolean) isSeed.invoke(null, "carrot") && (boolean) isSeed.invoke(null, "wheat_seeds") && !(boolean) isSeed.invoke(null, "wheat"), "seed items");
            Field seeds = fm.getDeclaredField("SEEDS"); seeds.setAccessible(true);
            check(((String[][]) seeds.get(null))[0][0].equals("carrot") && ((String[][]) seeds.get(null))[2][0].equals("wheat_seeds"), "carrots/potatoes before wheat");

            Class<?> hs = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.House");
            Method spots = hs.getDeclaredMethod("wallTorchSpots", int.class); spots.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<int[]> sp = (java.util.List<int[]>) spots.invoke(null, 9);
            boolean inside = sp.size() == 8;
            for (int[] t : sp) {
                inside &= t[0] >= 1 && t[0] <= 7 && t[1] >= 1 && t[1] <= 7;              // torch cell inside
                inside &= Math.abs(t[0] - t[2]) + Math.abs(t[1] - t[3]) == 1;           // hangs on the wall next to it
                inside &= (t[2] == 0 || t[2] == 8 || t[3] == 0 || t[3] == 8);           // that's a wall
                inside &= !((t[2] == 0 || t[2] == 8) && t[1] == 4);                     // not on a window
                inside &= !(t[3] == 0 && t[0] == 4);                                    // not over the door
            }
            check(inside, "8 wall torches, inside, on walls, clear of windows and door");
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails);
    }
    static class FakeMobs {
        static class Creeper {}
        static class AbstractSkeleton {}
        static class Stray extends AbstractSkeleton {}
        static class WitherSkeleton extends AbstractSkeleton {}
        static class Zombie {}
    }
}
