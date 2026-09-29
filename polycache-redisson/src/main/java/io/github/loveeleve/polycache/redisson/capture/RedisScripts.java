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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of the scripts loaded by {@code SCRIPT LOAD}, keyed by the SHA1 of the script computed
 * the same way as Redis does. It allows the capture to translate an {@code EVALSHA} command into
 * an {@code EVAL} command carrying the script body, so the replay does not depend on the script
 * cache of the target Redis.
 *
 * <p>The registry is bounded (the least recently used scripts are evicted) and thread-safe, because
 * the capture is invoked concurrently by the Netty event loops of the multiple connections.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
abstract class RedisScripts {

    /**
     * The maximum number of the cached scripts.
     */
    static final int MAX_SCRIPTS = 1024;

    private static final Logger logger = LoggerFactory.getLogger(RedisScripts.class);

    private static final Map<String, byte[]> SCRIPTS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_SCRIPTS;
                }
            });

    private RedisScripts() {
    }

    /**
     * Registers the script of a {@code SCRIPT LOAD} command.
     *
     * @param script the raw script argument
     * @return the SHA1 of the script, {@code null} if the argument is not script-like
     */
    static String register(Object script) {
        byte[] bytes = toBytes(script);
        if (bytes == null) {
            return null;
        }
        String sha = sha1(bytes);
        if (sha != null) {
            SCRIPTS.put(sha, bytes);
        }
        return sha;
    }

    /**
     * Finds the script body by its SHA1.
     *
     * @param sha1 the SHA1 of the script, as a hexadecimal {@link String} or its bytes
     * @return the script bytes, {@code null} if the script is unknown
     */
    static byte[] find(Object sha1) {
        String sha = toString(sha1);
        return sha == null ? null : SCRIPTS.get(sha.toLowerCase());
    }

    private static byte[] toBytes(Object script) {
        if (script instanceof byte[]) {
            return (byte[]) script;
        }
        if (script instanceof ByteBuf) {
            return ByteBufUtil.getBytes((ByteBuf) script);
        }
        if (script instanceof String) {
            return ((String) script).getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String toString(Object sha1) {
        if (sha1 instanceof String) {
            return (String) sha1;
        }
        if (sha1 instanceof byte[]) {
            return new String((byte[]) sha1, StandardCharsets.UTF_8);
        }
        if (sha1 instanceof ByteBuf) {
            return new String(ByteBufUtil.getBytes((ByteBuf) sha1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String sha1(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.warn("The SHA-1 algorithm is not available, the script cannot be recorded", e);
            return null;
        }
    }
}
