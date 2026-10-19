package com.myxhs.demo.leetcode.twopointers;

/**
 * LeetCode 42. 接雨水
 *
 * 公式：每格水 = min(左边最高, 右边最高) − 自己高度，总答案 = 全部相加。
 *
 * trapByFormula：公式的直译版（数组存每格左最/右最），对照理解用。
 * trap：两指针压缩版——数组用完就扔压成两个变量，min 被 if 的不等式证明吃掉。
 * 时间 O(n)，空间 O(1)（trap 版）。
 */
public class TrappingRainWater {

    /** 直译版：就是"每格算左最、右最、取 min、减自己、全部相加" */
    public int trapByFormula(int[] height) {
        int n = height.length;
        if (n == 0) {
            return 0;
        }
        int[] leftMax = new int[n];    // leftMax[i] = 0..i 的最高
        int[] rightMax = new int[n];   // rightMax[i] = i..n-1 的最高
        leftMax[0] = height[0];
        for (int i = 1; i < n; i++) {
            leftMax[i] = Math.max(leftMax[i - 1], height[i]);
        }
        rightMax[n - 1] = height[n - 1];
        for (int i = n - 2; i >= 0; i--) {
            rightMax[i] = Math.max(rightMax[i + 1], height[i]);
        }
        int ans = 0;
        for (int i = 0; i < n; i++) {
            ans += Math.min(leftMax[i], rightMax[i]) - height[i];  // ← 公式原样
        }
        return ans;
    }

    /** 两指针版（正解） */
    public int trap(int[] height) {
        int left = 0, right = height.length - 1;
        int leftMax = 0, rightMax = 0, ans = 0;
        while (left <= right) {
            if (leftMax < rightMax) {
                leftMax = Math.max(leftMax, height[left]);
                ans += leftMax - height[left];
                left++;
            } else {
                rightMax = Math.max(rightMax, height[right]);
                ans += rightMax - height[right];
                right--;
            }
        }
        return ans;
    }

    public static void main(String[] args) {
        TrappingRainWater solution = new TrappingRainWater();
        int[] height = {4, 2, 0, 3, 2, 5};
        System.out.println("输入: " + java.util.Arrays.toString(height));
        System.out.println("直译版: " + solution.trapByFormula(height));
        System.out.println("两指针: " + solution.trap(height));
    }
}
