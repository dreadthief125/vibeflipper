package dev.flipper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;
import java.lang.reflect.Field;
import java.util.regex.*;

/**
 * Every Minecraft-version-specific call lives here. If 26.2 renamed something, this is the only file to fix.
 */
public final class GameBridge {
    static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy): ([\\d,]+)");
    static final Pattern COINS = Pattern.compile("([\\d,]+) coins");
    static Minecraft mc() { return Minecraft.getInstance(); }

    public static void say(String s) {
        var m = mc();
        m.execute(() -> { if (m.player != null) m.player.displayClientMessage(Component.literal("§b[Flipper] §r" + s), false); });
    }
    public static void cmd(String c) { mc().getConnection().sendCommand(c); }   // no leading slash
    public static String title() { return mc().screen instanceof AbstractContainerScreen<?> s ? s.getTitle().getString() : ""; }
    public static void closeScreen() { var m = mc(); if (m.player != null && m.screen != null) m.player.closeContainer(); }

    public static long purse() {
        try {
            var sb = mc().level.getScoreboard();
            var obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj != null) for (var e : sb.listPlayerScores(obj)) {
                String line = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.owner()), Component.literal(e.owner())).getString();
                Matcher m = PURSE.matcher(line);
                if (m.find()) return Long.parseLong(m.group(1).replace(",", ""));
            }
        } catch (Throwable ignored) {}
        return FlipperMod.cfg.purseOverride > 0 ? FlipperMod.cfg.purseOverride : -1;
    }

    static Slot find(String name, boolean inventory) {
        var m = mc();
        if (!(m.screen instanceof AbstractContainerScreen<?> s)) return null;
        for (Slot sl : s.getMenu().slots) {
            if ((sl.container == m.player.getInventory()) != inventory) continue;
            ItemStack st = sl.getItem();
            if (!st.isEmpty() && st.getHoverName().getString().contains(name)) return sl;
        }
        return null;
    }
    static void click(Slot sl) {
        var m = mc();
        var menu = ((AbstractContainerScreen<?>) m.screen).getMenu();
        m.gameMode.handleInventoryMouseClick(menu.containerId, sl.index, 0, ClickType.PICKUP, m.player);
    }
    public static boolean clickByName(String name) { var s = find(name, false); if (s == null) return false; click(s); return true; }
    public static boolean clickInventoryItem(String name) { var s = find(name, true); if (s == null) return false; click(s); return true; }

    /** First "N coins" figure in the tooltip of the named slot, or -2 if the slot isn't there. */
    public static long slotCoins(String name) {
        var s = find(name, false);
        if (s == null) return -2;
        var sb = new StringBuilder();
        for (Component c : s.getItem().getTooltipLines(Item.TooltipContext.EMPTY, mc().player, TooltipFlag.NORMAL))
            sb.append(c.getString()).append('\n');
        Matcher m = COINS.matcher(sb);
        return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : -1;
    }

    public static boolean hasItem(String name) {
        var inv = mc().player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getHoverName().getString().contains(name)) return true;
        return false;
    }

    /** Fills the first line of an open sign editor and closes it (which sends the sign packet). */
    public static boolean fillSign(String text) {
        if (!(mc().screen instanceof AbstractSignEditScreen ss)) return false;
        try {
            for (Class<?> c = ss.getClass(); c != null; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields())
                    if (f.getType() == String[].class) {
                        f.setAccessible(true);
                        ((String[]) f.get(ss))[0] = text;
                        ss.onClose();
                        return true;
                    }
        } catch (Exception ignored) {}
        return false;
    }
}
