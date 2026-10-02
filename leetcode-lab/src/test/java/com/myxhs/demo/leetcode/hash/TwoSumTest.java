package com.myxhs.demo.leetcode.hash;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TwoSumTest {

    private final TwoSum solution = new TwoSum();

    @Test
    void testBasic() {
        int[] nums = {2, 7, 11, 15};
        int target = 9;
        int[] result = solution.twoSum(nums, target);
        Arrays.sort(result);
        assertArrayEquals(new int[]{0, 1}, result);
    }

    @Test
    void testDuplicateValues() {
        int[] nums = {3, 2, 4};
        int target = 6;
        int[] result = solution.twoSum(nums, target);
        Arrays.sort(result);
        assertArrayEquals(new int[]{1, 2}, result);
    }

    @Test
    void testSameElement() {
        int[] nums = {3, 3};
        int target = 6;
        int[] result = solution.twoSum(nums, target);
        Arrays.sort(result);
        assertArrayEquals(new int[]{0, 1}, result);
    }
}
