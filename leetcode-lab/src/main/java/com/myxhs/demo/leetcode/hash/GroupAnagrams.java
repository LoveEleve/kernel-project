package com.myxhs.demo.leetcode.hash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LeetCode 49. 字母异位词分组
 *
 * 思路：排序后的字符串作为哈希表的 key，相同 key 分到同一组。
 * 时间 O(n·k·logk)，空间 O(n·k)，n 是字符串个数，k 是字符串最大长度。
 */
public class GroupAnagrams {

    public List<List<String>> groupAnagrams(String[] strs) {
        Map<String, List<String>> map = new HashMap<>();
        for (String s : strs) {
            char[] chars = s.toCharArray();
            Arrays.sort(chars);
            String key = new String(chars);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return new ArrayList<>(map.values());
    }

    public static void main(String[] args) {
        GroupAnagrams solution = new GroupAnagrams();
        String[] strs = {"eat", "tea", "tan", "ate", "nat", "bat"};
        System.out.println("输入: " + Arrays.toString(strs));
        System.out.println("输出: " + solution.groupAnagrams(strs));
    }
}
