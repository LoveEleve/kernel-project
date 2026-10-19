package com.myxhs.demo.leetcode.hash;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * LeetCode 128. 最长连续序列
 *
 * 思路：HashSet 去重 + O(1) 查找，只从序列起点（num-1 不在集合中）开始计数。
 * 时间 O(n)，空间 O(n)。
 */
public class LongestConsecutiveSequence {

    public int longestConsecutive(int[] nums) {
        Set<Integer> set = new HashSet<>();
        for (int num : nums) {
            set.add(num);
        }
        int maxLen = 0;
        for (int num : set) {
            if (!set.contains(num - 1)) {
                int current = num;
                int len = 1;
                while (set.contains(current + 1)) {
                    current++;
                    len++;
                }
                maxLen = Math.max(maxLen, len);
            }
        }
        return maxLen;
    }

    public static void main(String[] args) {
        LongestConsecutiveSequence solution = new LongestConsecutiveSequence();
        int[] nums = {100, 4, 200, 1, 3, 2};
        System.out.println("输入: nums = " + Arrays.toString(nums));
        System.out.println("输出: " + solution.longestConsecutive(nums));
    }
}
