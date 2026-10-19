package com.myxhs.demo.leetcode.twopointers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreeSumTest {

    private final ThreeSum solution = new ThreeSum();

    /** 三元组顺序固定（题目要求去重），但外层列表顺序官方说任意，归一化后比较 */
    private List<List<Integer>> normalize(List<List<Integer>> lists) {
        List<List<Integer>> copy = new ArrayList<>();
        for (List<Integer> l : lists) {
            List<Integer> sorted = new ArrayList<>(l);
            sorted.sort(Comparator.naturalOrder());
            copy.add(sorted);
        }
        copy.sort(Comparator.comparing(a -> String.valueOf(a)));
        return copy;
    }

    @Test
    void testOfficialExample() {
        List<List<Integer>> result = solution.threeSum(new int[]{-1, 0, 1, 2, -1, -4});
        List<List<Integer>> expected = Arrays.asList(
                Arrays.asList(-1, -1, 2),
                Arrays.asList(-1, 0, 1));
        assertEquals(normalize(expected), normalize(result));
    }

    @Test
    void testNoResult() {
        assertEquals(List.of(), solution.threeSum(new int[]{0, 1, 1}));
    }

    @Test
    void testAllZero() {
        List<List<Integer>> result = solution.threeSum(new int[]{0, 0, 0});
        assertEquals(normalize(List.of(Arrays.asList(0, 0, 0))), normalize(result));
    }

    @Test
    void testAllPositive() {
        assertEquals(List.of(), solution.threeSum(new int[]{1, 2, 3}));
    }
}
