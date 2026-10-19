package com.myxhs.demo.leetcode.twopointers;

import java.util.Arrays;

/**
 * LeetCode 11. 盛最多水的容器
 *
 * 思路：对撞指针。面积 = 宽 × 两侧较矮的高；宽只会减小，
 * 所以每次移动较矮的一侧（才可能让面积变大），直到相遇。
 * 时间 O(n)，空间 O(1)。
 */
public class MaxArea {

    public int maxArea(int[] height) {
        int left = 0, right = height.length - 1;
        int max = 0;
        while (left < right) {
            int area = Math.min(height[left], height[right]) * (right - left);
            max = Math.max(max, area);
            if (height[left] < height[right]) {
                left++;
            } else {
                right--;
            }
        }
        return max;
    }

    public static void main(String[] args) {
        MaxArea solution = new MaxArea();
        int[] height = {1, 8, 6, 2, 5, 4, 8, 3, 7};
        System.out.println("输入: " + Arrays.toString(height));
        System.out.println("输出: " + solution.maxArea(height));
    }
}
