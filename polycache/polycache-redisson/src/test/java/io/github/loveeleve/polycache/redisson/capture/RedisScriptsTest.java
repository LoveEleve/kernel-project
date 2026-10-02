/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loveeleve.polycache.redisson.capture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisScripts} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisScriptsTest {

    @Test
    void testRegisterAndFind() throws Exception {
        String script = "return redis.call('get', KEYS[1])";
        String sha = RedisScripts.register(script);

        assertNotNull(sha);
        assertEquals(expectedSha1(script), sha);
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), RedisScripts.find(sha));
        // The SHA1 lookup is case insensitive
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), RedisScripts.find(sha.toUpperCase()));

        assertNull(RedisScripts.find("unknown-sha"));
        assertNull(RedisScripts.find(null));
        assertNull(RedisScripts.register(123));
        assertNull(RedisScripts.register(null));
    }

    @Test
    void testLeastRecentlyUsedEviction() {
        String firstSha = null;
        String lastSha = null;
        for (int i = 0; i <= RedisScripts.MAX_SCRIPTS; i++) {
            String sha = RedisScripts.register("return " + i);
            if (i == 0) {
                firstSha = sha;
            }
            lastSha = sha;
        }
        assertNull(RedisScripts.find(firstSha));
        assertNotNull(RedisScripts.find(lastSha));
    }

    private static String expectedSha1(String script) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
