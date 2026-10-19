package com.myxhs.demo.leetcode.hash;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupAnagramsTest {

    private final GroupAnagrams solution = new GroupAnagrams();

    /** 分组顺序任意，测试前先把每组内部和组之间都排序，保证可比 */
    private List<List<String>> normalize(List<List<String>> groups) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> g : groups) {
            List<String> sorted = new ArrayList<>(g);
            sorted.sort(Comparator.naturalOrder());
            copy.add(sorted);
        }
        copy.sort(Comparator.comparing(a -> a.isEmpty() ? "" : a.get(0)));
        return copy;
    }

    @Test
    void testBasic() {
        List<List<String>> result = solution.groupAnagrams(
                new String[]{"eat", "tea", "tan", "ate", "nat", "bat"});
        List<List<String>> expected = Arrays.asList(
                Arrays.asList("bat"),
                Arrays.asList("nat", "tan"),
                Arrays.asList("ate", "eat", "tea"));
        assertEquals(normalize(expected), normalize(result));
    }

    @Test
    void testEmptyString() {
        List<List<String>> result = solution.groupAnagrams(new String[]{""});
        assertEquals(1, result.size());
        assertEquals(Arrays.asList(""), result.get(0));
    }

    @Test
    void testSingleChar() {
        List<List<String>> result = solution.groupAnagrams(new String[]{"a"});
        assertEquals(1, result.size());
        assertEquals(Arrays.asList("a"), result.get(0));
    }
}
