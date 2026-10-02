package com.myxhs.demo.leetcode.slidingwindow;

import java.util.HashMap;
import java.util.Map;

/**
 * LeetCode 3. 无重复字符的最长子串
 * <p>
 * 给定一个字符串 s，找出其中不含重复字符的最长子串的长度。
 * <p>
 * 思路：滑动窗口。右指针扩张窗口，遇到重复字符时左指针收缩，
 * 用 HashMap 记录字符最近出现位置，实现 O(n) 时间。
 */
public class LongestSubstringWithoutRepeating {

    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> lastSeen = new HashMap<>();
        int maxLen = 0;
        int left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (lastSeen.containsKey(c) && lastSeen.get(c) >= left) {
                left = lastSeen.get(c) + 1;
            }
            lastSeen.put(c, right);
            maxLen = Math.max(maxLen, right - left + 1);
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
