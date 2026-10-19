package com.myxhs.demo.leetcode.twopointers;

import java.util.Arrays;

/**
 * LeetCode 283. 移动零
 *
 * 思路：快慢指针。fast 探路找非零，slow 记住"下一个非零该放哪"，找到就交换。
 * 不变式：[0, slow) 全是非零（已放好），[slow, fast) 全是零（待处理）。
 * 时间 O(n)，空间 O(1)。
 */
public class MoveZeroes {

    public void moveZeroes(int[] nums) {
        int slow = 0;
        for (int fast = 0; fast < nums.length; fast++) {
            if (nums[fast] != 0) {
                int temp = nums[slow];
                nums[slow] = nums[fast];
                nums[fast] = temp;
                slow++;
            }
        }
    }

    public static void main(String[] args) {
        MoveZeroes solution = new MoveZeroes();
        int[] nums = {0, 1, 0, 3, 12};
        System.out.println("输入: nums = " + Arrays.toString(nums));
        solution.moveZeroes(nums);
        System.out.println("输出: " + Arrays.toString(nums));
    }
}
