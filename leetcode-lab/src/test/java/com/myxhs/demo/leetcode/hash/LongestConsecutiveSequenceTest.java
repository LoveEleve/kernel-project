package com.myxhs.demo.leetcode.hash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LongestConsecutiveSequenceTest {

    private final LongestConsecutiveSequence solution = new LongestConsecutiveSequence();

    @Test
    void testBasic() {
        assertEquals(4, solution.longestConsecutive(new int[]{100, 4, 200, 1, 3, 2}));
    }

    @Test
    void testWithDuplicates() {
        assertEquals(9, solution.longestConsecutive(new int[]{0, 3, 7, 2, 5, 8, 4, 6, 0, 1}));
    }

    @Test
    void testEmpty() {
        assertEquals(0, solution.longestConsecutive(new int[]{}));
    }

    @Test
    void testSingle() {
        assertEquals(1, solution.longestConsecutive(new int[]{5}));
    }
}
