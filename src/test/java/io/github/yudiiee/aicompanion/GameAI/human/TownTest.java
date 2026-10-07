package io.github.yudiiee.aicompanion.GameAI.human;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The price list, money talk, trading maths, the town's layout and its designs. */
public class TownTest {
    static int fails = 0;

    static void check(boolean c, String msg) {
        if (!c) { fails++; System.out.println("FAIL: " + msg); } else System.out.println("ok: " + msg);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-4; }

    public static void main(String[] args) throws Exception {
        Path res = Paths.get(args.length > 0 ? args[0] : "src/src/main/resources/assets/ai-companion");

        // ---------------- the price list ----------------
        PriceBook.Price p = PriceBook.parseLine("PRICE | ID: minecraft:poplar_log | RATIO: 32 = 1 DIA | UNIT_DIA: 0.03125 | RARITY: T2_UNCOMMON | CAT: wood");
        check(p != null && p.item().equals("poplar_log") && p.qty() == 32 && near(p.unit(), 0.03125) && p.tier().equals("uncommon"), "price line parsed");
        PriceBook.Price ib = PriceBook.parseLine("PRICE | ID: minecraft:iron_block | RATIO: 1 = 1.125 DIA | UNIT_DIA: 1.12500 | RARITY: T4_PRECIOUS | CAT: metallurgy");
        check(ib != null && ib.qty() == 1 && near(ib.unit(), 1.125), "fractional ratio parsed");
        check(PriceBook.parseLine("# PRICE | comment") == null && PriceBook.parseLine("### SECTION 1") == null, "comments skipped");
        PriceBook.Price u = PriceBook.parseLine("PRICE | ID: minecraft:thing | UNIT_DIA: 0.25 | RARITY: T3_REFINED | CAT: misc");
        check(u != null && near(u.unit(), 0.25) && u.qty() == 4, "price from unit only");

        List<PriceBook.Price> all;
        try (InputStream in = Files.newInputStream(res.resolve("prices.txt"))) {
            all = PriceBook.read(in);
        }
        long lines = Files.readAllLines(res.resolve("prices.txt")).stream().filter(l -> l.startsWith("PRICE")).count();
        check(all.size() == lines && lines >= 270, "every line of the bundled list parses (" + all.size() + "/" + lines + ")");
        int badUnit = 0;
        for (String line : Files.readAllLines(res.resolve("prices.txt"))) {
            if (!line.startsWith("PRICE")) continue;
            PriceBook.Price x = PriceBook.parseLine(line);
            String ud = line.replaceAll(".*UNIT_DIA:\\s*([0-9.]+).*", "$1");
            if (Math.abs(x.unit() - Double.parseDouble(ud)) > 0.0001) badUnit++;
        }
        check(badUnit == 0, "ratios agree with the listed unit prices");
        PriceBook.use(all);
        check(PriceBook.size() == all.size(), "price book loaded");
        check(near(PriceBook.unit("bread"), 0.0625) && near(PriceBook.unit("minecraft:diamond"), 1.0), "listed prices");
        check(near(PriceBook.unit("netherite_block"), 144), "netherite block price");
        check(near(PriceBook.value("cobblestone", 128), 1.0), "128 cobblestone is a diamond");
        check(near(PriceBook.buyPrice("cobblestone", 128), 0.8), "shops buy at 80%");
        check(PriceBook.perDiamond("bread") == 16 && PriceBook.perDiamond("iron_block") == 0, "per diamond");

        // recipes known: things off the list are worth their ingredients
        List<RecipeBook.Recipe> recs;
        try (InputStream in = Files.newInputStream(res.resolve("recipes.txt"))) {
            recs = RecipeBook.read(in);
        }
        RecipeBook.use(recs);
        double sb = PriceBook.unit("stone_bricks");
        check(sb > 0 && near(sb, PriceBook.round(0.015625 * 1.1)), "stone bricks worked out from stone: " + sb);
        double torch = PriceBook.unit("torch");
        check(torch > 0 && torch < 0.05, "a torch is cheap: " + torch);
        check(PriceBook.unit("command_block") < 0, "no price for things it can't make");

        // what people call things
        check("iron_ingot".equals(PriceBook.itemFor("iron")), "iron means ingots");
        check("cobblestone".equals(PriceBook.itemFor("64 cobble")), "64 cobble");
        check("enchanted_golden_apple".equals(PriceBook.itemFor("a god apple")), "god apple");
        check("iron_pickaxe".equals(PriceBook.itemFor("iron pick")), "iron pick");
        check("bread".equals(PriceBook.itemFor("16 bread")), "16 bread");
        check("ender_pearl".equals(PriceBook.itemFor("ender pearls")), "plural");
        check("stone_bricks".equals(PriceBook.itemFor("stone bricks")), "recipe-priced item found");
        check(PriceBook.itemFor("a house") == null, "a house has no price");
        check(PriceBook.countIn("a stack of iron") == 64 && PriceBook.countIn("2 stacks of cobble") == 128
                && PriceBook.countIn("16 bread") == 16 && PriceBook.countIn("bread") == 0, "counts");

        check("a hopper".equals(PriceBook.question("how much is a hopper")) || "hopper".equals(PriceBook.question("how much is a hopper")),
                "how much is a hopper: " + PriceBook.question("how much is a hopper"));
        check("hopper".equals(PriceBook.question("how much does a hopper cost")), "how much does a hopper cost");
        check("diamonds".equals(PriceBook.question("what are diamonds worth")), "what are X worth");
        check("an elytra".equals(PriceBook.question("price of an elytra")) || "elytra".equals(PriceBook.question("price of an elytra")),
                "price of: " + PriceBook.question("price of an elytra"));
        check("64 cobblestone".equals(PriceBook.question("how much for 64 cobblestone")), "how much for 64 cobblestone");
        check(PriceBook.question("how much do you have") == null, "not a price question");
        check(PriceBook.question("how much longer") == null, "how much longer isn't a price");
        String ans = PriceBook.answer("64 cobblestone");
        check(ans != null && ans.startsWith("64 cobblestone is 0.5 of a diamond"), "answer with count: " + ans);
        check(PriceBook.answer("elytra").startsWith("1 elytra for 32 diamonds"), "answer: " + PriceBook.answer("elytra"));
        check(PriceBook.money(1) .equals("1 diamond") && PriceBook.money(2.25).equals("2.25 diamonds")
                && PriceBook.money(0.5).equals("0.5 of a diamond") && PriceBook.money(0.0625).equals("0.063 of a diamond"), "money: "
                + PriceBook.money(0.0625));
        check(PriceBook.plural("bread", 3).equals("bread") && PriceBook.plural("ender pearl", 2).equals("ender pearls")
                && PriceBook.plural("iron ingot", 2).equals("iron ingots"), "plurals");
        List<String> ment = PriceBook.mentioned("yo how much would you want for some golden apples and an elytra", 5);
        check(ment.size() >= 2 && ment.stream().anyMatch(s -> s.startsWith("elytra")), "prices for the prompt: " + ment);

        // ---------------- trading ----------------
        check(Economy.parse("sell me 16 bread") != null && Economy.parse("sell me 16 bread").kind() == Economy.Kind.SELL, "sell me");
        check(Economy.parse("can i buy a stack of iron").kind() == Economy.Kind.SELL, "can i buy");
        check(Economy.parse("i want to buy 3 ender pearls").kind() == Economy.Kind.SELL, "i want to buy");
        check(Economy.parse("buy my 64 cobblestone").kind() == Economy.Kind.BUY, "buy my");
        check(Economy.parse("do you want to buy my iron").kind() == Economy.Kind.BUY, "do you want to buy my");
        check(Economy.parse("i want to sell 10 gold").kind() == Economy.Kind.BUY, "i want to sell");
        check(Economy.parse("what do you sell").kind() == Economy.Kind.STOCK, "stock");
        check(Economy.parse("how many diamonds do you have").kind() == Economy.Kind.WALLET, "wallet");
        check(Economy.parse("what's my tab").kind() == Economy.Kind.CREDIT, "tab");
        check(Economy.parse("cash out").kind() == Economy.Kind.CASHOUT, "cash out");
        check(Economy.parse("sell me your house") == null, "not goods");
        check(Economy.parse("i'd like to build a house") == null, "not a trade");
        check(Economy.parse("give me your diamonds") == null && Economy.parse("store your diamonds") == null, "diamond chores aren't trades");
        check(Economy.parse("give me my diamonds") == null, "give me my diamonds is not a cash out");
        check(Economy.parse("sell me 16 bread please") != null && "bread".equals(PriceBook.itemFor("16 bread please")), "trailing please");
        check("bread".equals(PriceBook.itemFor("bread for a diamond")) && "bread".equals(PriceBook.itemFor("half a stack of bread")), "filler stripped");
        check(PriceBook.countIn("bread x64") == 64 && PriceBook.countIn("half a stack of bread") == 32, "x64 and half a stack");
        check(Economy.parse("how about iron") == null, "how about iron isn't a purchase");
        check(Economy.parse("what's your balance").kind() == Economy.Kind.WALLET, "what's your balance");
        check(Economy.isYes("deal") && Economy.isYes("ok") && Economy.isYes("yes please") && !Economy.isYes("ok go mining"), "yes");
        check(Economy.isNo("no deal") && Economy.isNo("nah") && !Economy.isNo("no way that's cheap lol"), "no");
        double[] pf = Economy.payFor(0.625, 0);
        check(pf[0] == 1 && near(pf[1], 0.375), "10 bread: 1 diamond, 0.375 on the tab");
        pf = Economy.payFor(0.625, 0.5);
        check(pf[0] == 1 && near(pf[1], 0.875), "with half a diamond on the tab");
        pf = Economy.payFor(0.25, 0.5);
        check(pf[0] == 0 && near(pf[1], 0.25), "comes off the tab");
        pf = Economy.payFor(3.0, 0);
        check(pf[0] == 3 && near(pf[1], 0), "whole diamonds");
        double[] po = Economy.payOut(2.4, 0.7, 10);
        check(po[0] == 3 && near(po[1], 0.1), "paying out: 3 now, 0.1 on the tab");
        po = Economy.payOut(5.5, 0, 2);
        check(po[0] == 2 && near(po[1], 3.5), "short of diamonds: the rest on the tab");

        // ---------------- the town layout ----------------
        Map<String, CityPlan.Design> d = new HashMap<>();
        CityPlan.Design plaza = new CityPlan.Design("plaza", "city_plaza.nbt", 15, 15, "south");
        d.put("warehouse", new CityPlan.Design("warehouse", "w", 11, 9, "south"));
        d.put("shop", new CityPlan.Design("shop", "s", 9, 9, "south"));
        d.put("farm", new CityPlan.Design("farm", "f", 13, 11, "south"));
        d.put("temple", new CityPlan.Design("temple", "t", 15, 23, "south"));
        d.put("mall", new CityPlan.Design("mall", "m", 21, 15, "south"));
        d.put("amphitheatre", new CityPlan.Design("amphitheatre", "a", 25, 21, "south"));
        List<CityPlan.Design> houses = List.of(new CityPlan.Design("house", "h1", 18, 22, "east"),
                new CityPlan.Design("house", "h2", 23, 25, "north"), new CityPlan.Design("house", "h3", 22, 25, "north"),
                new CityPlan.Design("house", "h4", 21, 31, "west"));
        List<CityPlan.Lot> lots = CityPlan.layout(plaza, CityPlan.program(d, 3, houses));
        check(lots.get(0).kind().equals("plaza") && lots.get(0).x0() == -7 && lots.get(0).z0() == -7, "plaza in the middle");
        int overlaps = 0;
        for (int i = 0; i < lots.size(); i++) for (int j = i + 1; j < lots.size(); j++) {
            CityPlan.Lot a = lots.get(i), b = lots.get(j);
            if (a.overlaps(b)) { overlaps++; System.out.println("  overlap: " + a + " / " + b); }
        }
        check(overlaps == 0, "nothing overlaps (" + lots.size() + " pieces)");
        int badSize = 0, badFace = 0;
        Map<String, CityPlan.Design> byFile = new HashMap<>();
        for (CityPlan.Design x : d.values()) byFile.put(x.file(), x);
        for (CityPlan.Design x : houses) byFile.put(x.file(), x);
        for (CityPlan.Lot l : lots) {
            if (l.road() || l.kind().equals("plaza")) continue;
            CityPlan.Design x = byFile.get(l.file());
            int w = l.rot() % 2 == 1 ? x.sz() : x.sx(), len = l.rot() % 2 == 1 ? x.sx() : x.sz();
            if (w != l.w() || len != l.l()) { badSize++; System.out.println("  size: " + l + " vs " + x); }
            if (!Schematic.turnDir(x.front(), l.rot()).equals(l.face())) badFace++;
            // the front faces the road: one step out of the front is on the road's line (x or z == 0 side)
            int[] v = CityPlan.vec(l.face());
            int fx = v[0] > 0 ? l.x1() + 1 : v[0] < 0 ? l.x0() - 1 : (l.x0() + l.x1()) / 2;
            int fz = v[1] > 0 ? l.z1() + 1 : v[1] < 0 ? l.z0() - 1 : (l.z0() + l.z1()) / 2;
            boolean towardRoad = Math.abs(fx) < Math.abs((l.x0() + l.x1()) / 2.0) || Math.abs(fz) < Math.abs((l.z0() + l.z1()) / 2.0);
            if (!towardRoad) { badFace++; System.out.println("  faces away: " + l); }
        }
        check(badSize == 0, "turned sizes fit the lots");
        check(badFace == 0, "every building faces the road");
        CityPlan.Lot temple = null, mall = null, amph = null;
        int shops = 0, farms = 0, roads = 0;
        for (CityPlan.Lot l : lots) {
            switch (l.kind()) {
                case "temple" -> temple = l;
                case "mall" -> mall = l;
                case "amphitheatre" -> amph = l;
                case "shop" -> shops++;
                case "farm" -> farms++;
                case "road" -> roads++;
                default -> { }
            }
        }
        check(temple != null && temple.z1() < 0 && temple.face().equals("south"), "temple at the north end, facing the plaza");
        check(mall != null && mall.x0() > 0 && mall.face().equals("west"), "mall at the east end facing the plaza");
        check(amph != null && amph.z0() > 0 && amph.face().equals("north"), "amphitheatre at the south end");
        check(shops == 3 && farms == 3 && roads >= 8, "3 shops, 3 farms, " + roads + " bits of road");
        // a road leads up to every building before it in the order
        int[] reach = new int[4];
        boolean roadsFirst = true;
        for (CityPlan.Lot l : lots) {
            if (l.arm() < 0) continue;
            int far = switch (l.arm()) {
                case 0 -> -l.z0();
                case 1 -> l.x1();
                case 2 -> l.z1();
                default -> -l.x0();
            };
            if (l.road()) reach[l.arm()] = Math.max(reach[l.arm()], far);
            else {
                int near = switch (l.arm()) {
                    case 0 -> -l.z1();
                    case 1 -> l.x0();
                    case 2 -> l.z0();
                    default -> -l.x1();
                };
                if (reach[l.arm()] < near - CityPlan.GAP - 1 && !(l.kind().equals("temple") || l.kind().equals("mall") || l.kind().equals("amphitheatre"))) {
                    roadsFirst = false;
                    System.out.println("  no road yet to " + l + " (road reaches " + reach[l.arm()] + ")");
                }
            }
        }
        check(roadsFirst, "the road out to a building comes before it");
        int[] bnd = CityPlan.bounds(lots);
        check(bnd[2] - bnd[0] < 260 && bnd[3] - bnd[1] < 260, "town size " + (bnd[2] - bnd[0]) + "x" + (bnd[3] - bnd[1]));
        // roads run along the axes
        boolean straight = true;
        for (CityPlan.Lot l : lots) {
            if (!l.road()) continue;
            boolean ew = l.face().equals("east") || l.face().equals("west");
            if (ew && !(l.z0() == -3 && l.z1() == 3)) straight = false;
            if (!ew && !(l.x0() == -3 && l.x1() == 3)) straight = false;
        }
        check(straight, "roads run straight out of the plaza");

        // ---------------- remembering it ----------------
        City.Plot plot = new City.Plot(4, "shop", "city_shop.nbt", 10, -20, 9, 9, "north", 2, 1, 64, "Bro", "todo");
        City.Plot back = City.Plot.parse(plot.line());
        check(back != null && back.kind.equals("shop") && back.x0 == 10 && back.y == 64 && back.owner.equals("Bro")
                && back.state.equals("todo") && back.face.equals("north"), "plot saved and read back");
        City.Plot noY = City.Plot.parse(new City.Plot(5, "road", "", 0, 0, 16, 7, "east", 0, 1, City.NO_Y, "", "done").line());
        check(noY != null && noY.y == City.NO_Y && noY.owner.isEmpty() && noY.done(), "unset height and owner");
        check(plot.label().equals("Bro's shop") && noY.label().equals("the east road"), "labels");

        // ---------------- talking about the town ----------------
        check(City.parse("let's build a city", null, null) != null, "let's build a city");
        check(City.parse("build a town here", null, null) != null, "build a town here");
        check(City.parse("guys build a massive city called stone haven", null, null) != null, "called ...");
        check(City.parse("how's the town going", null, null) != null, "status");
        check(City.parse("where's the temple", null, null) != null, "where");
        check(City.parse("go to the temple", null, null) != null, "temple visit");
        check(City.parse("work on the city", null, null) != null, "work on it");
        check(City.parse("build a house", null, null) == null && City.parse("build the iron farm", null, null) == null, "not about the town");
        check(City.parse("stop building the town", null, null) == null && City.parse("cancel the town house", null, null) == null,
                "stopping isn't forgetting the town");
        check(City.parse("finish the town hall", null, null) == null, "finish the town hall isn't town work");
        check(City.parse("forget the town", null, null) != null, "forget the town");
        check(City.isOffering("bread") && City.isOffering("wheat") && !City.isOffering("diamond"), "offerings are food");

        // ---------------- chat routing ----------------
        check(HumanChatListener.classifyLocal("how much is a diamond pickaxe", "Bro") == HumanChatListener.Local.PRICE, "price question routed");
        check(HumanChatListener.classifyLocal("sell me 10 bread", "Bro") == HumanChatListener.Local.TRADE, "trade routed");
        check(HumanChatListener.classifyLocal("Bro what do you sell", "Bro") == HumanChatListener.Local.TRADE, "stock routed");
        check(HumanChatListener.classifyLocal("let's build a city", "Bro") == HumanChatListener.Local.CITY, "city routed");
        check(HumanChatListener.classifyLocal("build the temple", "Bro") == HumanChatListener.Local.BLUEPRINT
                || HumanChatListener.classifyLocal("build the temple", "Bro") == null, "a single temple is still a blueprint");
        check(HumanChatListener.classifyLocal("yes", "Bro") != HumanChatListener.Local.DEAL, "yes without an offer isn't a deal");
        check(HumanChatListener.classifyLocal("how do i make a hopper", "Bro") == HumanChatListener.Local.RECIPE, "recipes still work");

        // ---------------- the designs ----------------
        Path schem = res.resolve("schematics");
        String[] kinds = {"plaza", "temple", "amphitheatre", "shop", "mall", "farm", "warehouse"};
        for (String k : kinds) {
            Path f = schem.resolve("city_" + k + ".nbt");
            Schematic s = Schematic.load(f);
            Map<String, Integer> bill = BlueprintBuilder.billOf(s);
            int unobtainable = 0;
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                Schematic.State st = s.at(x, y, z);
                if (st != null && Schematic.Rules.kind(st) == Schematic.Rules.Kind.UNOBTAINABLE) unobtainable++;
            }
            List<String> hard = new ArrayList<>();
            for (String item : bill.keySet()) if (!BlueprintBuilder.obtainable(item, 0)) hard.add(item);
            if (!hard.isEmpty()) System.out.println("  (" + k + ": not obtainable in a test run: " + hard + ")");
            check(s.solidCount() > 100 && s.solidCount() < BlueprintBuilder.MAX_BLOCKS, k + ": " + s.sx + "x" + s.sy + "x" + s.sz + ", " + s.solidCount() + " blocks");
            check(unobtainable == 0, k + ": everything in it can be had " + bill.keySet());
            String side = Files.readString(schem.resolve("city_" + k + ".txt"));
            check(side.contains("front: south"), k + ": front is south");
        }
        Schematic shop = Schematic.load(schem.resolve("city_shop.nbt"));
        int chests = 0, barrels = 0;
        for (int y = 0; y < shop.sy; y++) for (int z = 0; z < shop.sz; z++) for (int x = 0; x < shop.sx; x++) {
            Schematic.State st = shop.at(x, y, z);
            if (st == null) continue;
            if (st.path().equals("chest")) chests++;
            if (st.path().equals("barrel")) barrels++;
        }
        check(chests == 3 && barrels == 1, "shop: 3 stock chests and a till");
        Schematic tmp = Schematic.load(schem.resolve("city_temple.nbt"));
        boolean altar = false;
        for (int y = 0; y < tmp.sy; y++) for (int z = 0; z < tmp.sz; z++) for (int x = 0; x < tmp.sx; x++) {
            Schematic.State st = tmp.at(x, y, z);
            if (st != null && st.path().equals("chest")) altar = true;
        }
        check(altar, "temple has its offering chest");
        Schematic pl = Schematic.load(schem.resolve("city_plaza.nbt"));
        boolean keepsUnder = pl.at(0, 0, 0) == null && pl.at(7, 0, 7) != null;
        check(keepsUnder, "plaza leaves the ground under it alone except under the fountain");
        Schematic amp = Schematic.load(schem.resolve("city_amphitheatre.nbt"));
        check(amp.at(0, 0, 0) == null, "amphitheatre doesn't dig out its corners");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
