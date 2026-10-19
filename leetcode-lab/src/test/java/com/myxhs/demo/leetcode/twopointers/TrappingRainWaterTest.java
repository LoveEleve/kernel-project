package com.myxhs.demo.leetcode.twopointers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrappingRainWaterTest {

    private final TrappingRainWater solution = new TrappingRainWater();

    @Test
    void testOfficialExample1() {
        assertEquals(6, solution.trap(new int[]{0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1}));
    }

    @Test
    void testOfficialExample2() {
        assertEquals(9, solution.trap(new int[]{4, 2, 0, 3, 2, 5}));
    }

    @Test
    void testEmpty() {
        assertEquals(0, solution.trap(new int[]{}));
    }

    @Test
    void testSingle() {
        assertEquals(0, solution.trap(new int[]{5}));
    }

    /** 直译版（公式）和两指针版必须给出相同答案 */
    @Test
    void testFormulaMatchesTwoPointer() {
        int[][] cases = {
                {0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1},
                {4, 2, 0, 3, 2, 5},
                {},
                {5},
                {5, 4, 3, 2, 1},
                {1, 2, 3, 4, 5},
                {2, 1, 2},
                {3, 0, 2, 4, 1, 0, 5}
        };
        for (int[] c : cases) {
            assertEquals(solution.trapByFormula(c), solution.trap(c),
                    "case: " + java.util.Arrays.toString(c));
        }
    }
}
