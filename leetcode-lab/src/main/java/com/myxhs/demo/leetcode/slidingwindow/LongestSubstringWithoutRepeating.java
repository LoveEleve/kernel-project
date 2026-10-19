package com.myxhs.demo.leetcode.slidingwindow;

import java.util.HashMap;
import java.util.Map;

/**
 * LeetCode 3. 无重复字符的最长子串
 *
 * 思路：滑动窗口三步曲——right 吸收字符进窗口（计数+1），
 * 出现重复就 left 收缩吐出字符（计数-1），每次合法后更新答案。
 * 时间 O(n)，空间 O(字符集大小)。
 */
public class LongestSubstringWithoutRepeating {

    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> count = new HashMap<>();
        int left = 0, maxLen = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            count.put(c, count.getOrDefault(c, 0) + 1);   // 进：计数+1
            while (count.get(c) > 1) {                    // 新字符重复 → 收
                char d = s.charAt(left);
                count.put(d, count.get(d) - 1);           // 吐出左端字符
                left++;
            }
            maxLen = Math.max(maxLen, right - left + 1);  // 记：合法窗口长度
        }
        return maxLen;
    }

    public static void main(String[] args) {
        LongestSubstringWithoutRepeating solution = new LongestSubstringWithoutRepeating();
        String s = "abcabcbb";
        System.out.println("输入: s = \"" + s + "\"");
        System.out.println("输出: " + solution.lengthOfLongestSubstring(s));
    }
}
