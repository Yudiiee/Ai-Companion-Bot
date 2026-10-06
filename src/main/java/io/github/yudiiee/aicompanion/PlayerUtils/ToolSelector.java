package io.github.yudiiee.aicompanion.PlayerUtils;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class ToolSelector {

    private static final int HOTBAR = 9;
    private static final int MAIN = 36;

    public static ItemStack selectBestToolForBlock(ServerPlayer bot, BlockState blockState) {
        List<ItemStack> hotbarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack bestTool = ItemStack.EMPTY;
        float highestSpeed = 0.0f;

        for (ItemStack item : hotbarItems) {
            if (item.isEmpty()) continue;

            float speed = item.getDestroySpeed(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
            }
        }

        // If none has a speed > 1.0, just use whatever is selected
        if (highestSpeed <= 1.0f) {
            return hotBarUtils.getSelectedHotbarItemStack(bot);
        }

        return bestTool;
    }

    /**
     * Puts the best tool for {@code blockState} in the bot's hand, the way a player would:
     * the fastest tool anywhere in the inventory is moved to the hotbar if needed and
     * selected. If no tool helps, the bot mines with an empty hand instead of whatever
     * junk (a stick, dirt...) it happened to be holding. Weapons and tools are never
     * pushed out of the hotbar to make room if there's another option. Server thread.
     */
    public static void equipBestTool(ServerPlayer bot, BlockState blockState) {
        try {
            equip(bot, blockState);
        } catch (Throwable t) {
            // never let tool juggling stop the mining itself
        }
    }

    private static void equip(ServerPlayer bot, BlockState blockState) {
        Inventory inv = bot.getInventory();
        int size = Math.min(MAIN, inv.getContainerSize());
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(blockState);
            // prefer the hotbar copy when two tools are equally good
            if (speed > bestSpeed || (speed == bestSpeed && best >= HOTBAR && i < HOTBAR && speed > 1.0f)) {
                bestSpeed = speed;
                best = i;
            }
        }

        if (best >= 0) {
            if (best < HOTBAR) {
                inv.setSelectedSlot(best);
                return;
            }
            int target = emptyHotbarSlot(inv);
            if (target < 0) target = junkHotbarSlot(inv);
            if (target < 0) target = inv.getSelectedSlot();
            ItemStack tool = inv.getItem(best);
            ItemStack displaced = inv.getItem(target);
            inv.setItem(target, tool);
            inv.setItem(best, displaced);
            inv.setSelectedSlot(target);
            inv.setChanged();
            return;
        }

        // No tool helps: use a bare hand.
        int empty = emptyHotbarSlot(inv);
        if (empty >= 0) {
            inv.setSelectedSlot(empty);
            return;
        }
        int junk = junkHotbarSlot(inv);
        int free = emptyMainSlot(inv, size);
        if (junk >= 0 && free >= 0) {
            inv.setItem(free, inv.getItem(junk));
            inv.setItem(junk, ItemStack.EMPTY);
            inv.setSelectedSlot(junk);
            inv.setChanged();
        } else if (junk >= 0) {
            inv.setSelectedSlot(junk); // full inventory: at least don't wear down a tool
        }
    }

    private static int emptyHotbarSlot(Inventory inv) {
        int sel = inv.getSelectedSlot();
        if (sel >= 0 && sel < HOTBAR && inv.getItem(sel).isEmpty()) return sel;
        for (int i = 0; i < HOTBAR; i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    /** A hotbar slot holding something that isn't a tool, weapon or other damageable item. */
    private static int junkHotbarSlot(Inventory inv) {
        int sel = inv.getSelectedSlot();
        if (sel >= 0 && sel < HOTBAR && isJunk(inv.getItem(sel))) return sel;
        for (int i = 0; i < HOTBAR; i++) if (isJunk(inv.getItem(i))) return i;
        return -1;
    }

    private static boolean isJunk(ItemStack s) {
        if (s.isEmpty() || s.getMaxDamage() > 0) return false;
        if (s.get(DataComponents.FOOD) != null) return false; // keep food handy
        String n = s.getHoverName().getString().toLowerCase(Locale.ROOT);
        return !(n.contains("arrow") || n.contains("pearl") || n.contains("totem") || n.contains("firework")
                || n.contains("potion") || n.contains("torch") || n.contains("bucket"));
    }

    private static int emptyMainSlot(Inventory inv, int size) {
        for (int i = HOTBAR; i < size; i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }
}
