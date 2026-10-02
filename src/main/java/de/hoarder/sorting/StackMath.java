package de.hoarder.sorting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Pure stack arithmetic, extracted from FullReorganizeTask so it can be unit
 * tested without a Bukkit runtime. This is the logic whose material-only
 * keying caused the 1.0.2 shulker content loss.
 */
public final class StackMath {

    private StackMath() {
    }

    /**
     * Tally amounts by a normalized key, preserving first-seen order.
     * Two entries are merged only if their normalized keys are equal -
     * distinct keys (e.g. same-colored shulker boxes with different
     * contents) always stay separate.
     */
    public static <T> LinkedHashMap<T, Integer> tally(Iterable<T> items,
                                                      Function<T, T> normalize,
                                                      ToIntFunction<T> amount) {
        LinkedHashMap<T, Integer> totals = new LinkedHashMap<>();
        for (T item : items) {
            totals.merge(normalize.apply(item), amount.applyAsInt(item), Integer::sum);
        }
        return totals;
    }

    /**
     * Split a total amount into stack-sized chunks (largest first).
     * splitAmounts(130, 64) -> [64, 64, 2]; maxStack < 1 is treated as 1.
     */
    public static List<Integer> splitAmounts(int total, int maxStack) {
        int max = Math.max(1, maxStack);
        List<Integer> amounts = new ArrayList<>();
        while (total > 0) {
            int amount = Math.min(total, max);
            amounts.add(amount);
            total -= amount;
        }
        return amounts;
    }
}
