package com.myxhs.demo.leetcode.hash;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * LeetCode 1. 两数之和
 * <p>
 * 给定一个整数数组 nums 和一个整数目标值 target，
 * 在数组中找出和为目标值的那两个整数，并返回它们的数组下标。
 * <p>
 * 思路：哈希表一次遍历。对于每个 nums[i]，查 target - nums[i] 是否已出现过。
 * 时间 O(n)，空间 O(n)。
 */
public class TwoSum {

    public int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int complement = target - nums[i];
            if (seen.containsKey(complement)) {
                return new int[]{seen.get(complement), i};
            }
            seen.put(nums[i], i);
        }
        return new int[0];
    }

    public static void main(String[] args) {
        TwoSum solution = new TwoSum();
        int[] nums = {2, 7, 11, 15};
        int target = 9;
        int[] result = solution.twoSum(nums, target);
        System.out.println("输入: nums = " + Arrays.toString(nums) + ", target = " + target);
        System.out.println("输出: " + Arrays.toString(result));
    }
}
