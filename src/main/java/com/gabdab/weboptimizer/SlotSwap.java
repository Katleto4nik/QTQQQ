package com.gabdab.weboptimizer;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

/** Швидке взяття предмета в руку і повернення назад. */
final class SlotSwap {
    /** Слот, який був обраний до першої підміни (-1 = нічого не підмінено). */
    static int prevSlot = -1;

    private SlotSwap() {}

    static void reset() {
        prevSlot = -1;
    }

    static int find(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    static void select(PlayerInventory inv, int slot) {
        if (prevSlot < 0) {
            prevSlot = inv.getSelectedSlot();
        }
        inv.setSelectedSlot(slot);
    }

    /** Скасування без дії: точно в той слот, що був. */
    static void abort(PlayerInventory inv) {
        if (prevSlot >= 0) {
            inv.setSelectedSlot(prevSlot);
            prevSlot = -1;
        }
    }

    /** Після дії: якщо увімкнено "Повертати меч" - у меч, інакше лишаємось у руці. */
    static void finish(PlayerInventory inv, boolean returnSword) {
        if (prevSlot >= 0) {
            if (returnSword) {
                inv.setSelectedSlot(swordSlot(inv, prevSlot));
            }
            prevSlot = -1;
        }
    }

    private static int swordSlot(PlayerInventory inv, int previous) {
        if (inv.getStack(previous).isIn(ItemTags.SWORDS)) {
            return previous;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                return i;
            }
        }
        return previous;
    }
}
