package com.myxhs.demo.leetcode.twopointers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LeetCode 15. 三数之和（双指针版：排序 + 固定 + 对撞 + 跳过相同值去重）
 *
 * 思路：固定一个数 a，剩下就是两数之和（对撞指针）；去重不依赖 Set，
 * 三层都跳过相同值：固定值重复整轮跳过，找到后把左右挨着的相同值跳完。
 * 时间 O(n²)，空间 O(1)（不计返回结果）。
 */
public class ThreeSum {

    public List<List<Integer>> threeSum(int[] nums) {
        Arrays.sort(nums);
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < nums.length - 2; i++) {
            if (i > 0 && nums[i] == nums[i - 1]) {
                continue;
            }
            int target = -nums[i];
            int left = i + 1, right = nums.length - 1;
            while (left < right) {
                int sum = nums[left] + nums[right];
                if (sum == target) {
                    result.add(Arrays.asList(nums[i], nums[left], nums[right]));
                    while (left < right && nums[left] == nums[left + 1]) left++;
                    while (left < right && nums[right] == nums[right - 1]) right--;
                    left++;
                    right--;
                } else if (sum < target) {
                    left++;
                } else {
                    right--;
                }
            }
        }
        return result;
    }

    public static void main(String[] args) {
        ThreeSum solution = new ThreeSum();
        int[] nums = {-1, 0, 1, 2, -1, -4};
        System.out.println("输入: " + Arrays.toString(nums));
        System.out.println("输出: " + solution.threeSum(nums));
    }
}
