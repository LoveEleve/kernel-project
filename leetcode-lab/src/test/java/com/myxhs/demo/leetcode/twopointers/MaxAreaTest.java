package com.myxhs.demo.leetcode.twopointers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaxAreaTest {

    private final MaxArea solution = new MaxArea();

    @Test
    void testBasic() {
        assertEquals(49, solution.maxArea(new int[]{1, 8, 6, 2, 5, 4, 8, 3, 7}));
    }

    @Test
    void testTwoBars() {
        assertEquals(1, solution.maxArea(new int[]{1, 1}));
    }

    @Test
    void testIncreasing() {
        // 三种组合：(1,2)*1=1, (1,3)*2=2, (2,3)*1=2 → 最大 2
        assertEquals(2, solution.maxArea(new int[]{1, 2, 3}));
    }

    @Test
    void testFlat() {
        // 两端 4,4 宽 4 → 4*4=16，比内部任何组合都大
        assertEquals(16, solution.maxArea(new int[]{4, 3, 2, 1, 4}));
    }
}
