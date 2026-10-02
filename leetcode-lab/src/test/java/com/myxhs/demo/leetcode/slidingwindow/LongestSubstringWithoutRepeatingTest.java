package com.myxhs.demo.leetcode.slidingwindow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LongestSubstringWithoutRepeatingTest {

    private final LongestSubstringWithoutRepeating solution = new LongestSubstringWithoutRepeating();

    @Test
    void testBasic() {
        assertEquals(3, solution.lengthOfLongestSubstring("abcabcbb"));
    }

    @Test
    void testAllSame() {
        assertEquals(1, solution.lengthOfLongestSubstring("bbbbb"));
    }

    @Test
    void testMixed() {
        assertEquals(3, solution.lengthOfLongestSubstring("pwwkew"));
    }

    @Test
    void testEmpty() {
        assertEquals(0, solution.lengthOfLongestSubstring(""));
    }
}
