package de.hoarder.sorting;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the stack merge arithmetic. The 1.0.2 shulker bug
 * (same-colored boxes with different contents collapsed into one) is the
 * "distinct keys stay distinct" case below.
 */
class StackMathTest {

    /** Stand-in for an ItemStack: type + payload + amount. */
    private record Item(String type, String payload, int amount) {
        Item normalized() {
            return new Item(type, payload, 1);
        }
    }

    private static LinkedHashMap<Item, Integer> tally(List<Item> items) {
        return StackMath.tally(items, Item::normalized, Item::amount);
    }

    @Test
    void distinctPayloadsStaySeparate() {
        // Two purple shulkers, different contents -> must NOT merge (the 1.0.2 bug)
        var result = tally(List.of(
            new Item("purple_shulker_box", "3x diamond", 1),
            new Item("purple_shulker_box", "5x iron", 1)
        ));

        assertEquals(2, result.size(), "distinct payloads must stay separate stacks");
        assertTrue(result.containsKey(new Item("purple_shulker_box", "3x diamond", 1)));
        assertTrue(result.containsKey(new Item("purple_shulker_box", "5x iron", 1)));
    }

    @Test
    void identicalItemsAreSummed() {
        var result = tally(List.of(
            new Item("stone", "", 40),
            new Item("stone", "", 30),
            new Item("dirt", "", 5)
        ));

        assertEquals(2, result.size());
        assertEquals(70, result.get(new Item("stone", "", 1)));
        assertEquals(5, result.get(new Item("dirt", "", 1)));
    }

    @Test
    void tallyPreservesFirstSeenOrder() {
        var result = tally(List.of(
            new Item("b", "", 1),
            new Item("a", "", 1),
            new Item("b", "", 1)
        ));

        assertEquals(List.of("b", "a"), result.keySet().stream().map(Item::type).toList());
    }

    @Test
    void tallyNormalizesAmountsIntoKeys() {
        // Same item with different amounts must land on one key
        var result = tally(List.of(
            new Item("stone", "", 64),
            new Item("stone", "", 1)
        ));
        assertEquals(1, result.size());
        assertEquals(65, result.values().iterator().next());
    }

    @Test
    void splitAmountsFillsFullStacksFirst() {
        assertEquals(List.of(64, 64, 2), StackMath.splitAmounts(130, 64));
        assertEquals(List.of(64), StackMath.splitAmounts(64, 64));
        assertEquals(List.of(1), StackMath.splitAmounts(1, 64));
    }

    @Test
    void splitAmountsHandlesUnstackables() {
        // maxStack 1 (tools, boats, filled shulkers)
        assertEquals(List.of(1, 1, 1), StackMath.splitAmounts(3, 1));
    }

    @Test
    void splitAmountsEdgeCases() {
        assertTrue(StackMath.splitAmounts(0, 64).isEmpty(), "zero items -> no stacks");
        // defensive: nonsensical maxStack is clamped to 1 instead of looping forever
        assertEquals(List.of(1, 1), StackMath.splitAmounts(2, 0));
    }

    @Test
    void identityNormalizeReproducesOldBugShape() {
        // Documents what the old Material-only keying did: with a normalize
        // function that erases the payload, the two shulkers WOULD merge.
        Function<Item, Item> materialOnly = i -> new Item(i.type(), "", 1);
        var result = StackMath.tally(List.of(
            new Item("purple_shulker_box", "3x diamond", 1),
            new Item("purple_shulker_box", "5x iron", 1)
        ), materialOnly, Item::amount);

        assertEquals(1, result.size(),
            "material-only keying merges distinct payloads - this is exactly why asOne() is used in production");
    }
}
