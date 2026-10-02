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
package io.github.loveeleve.polyzone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;

import static io.github.loveeleve.polyzone.Strings.isNotBlank;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The utilities class for simple HTTP requests, used by the cloud metadata endpoint locators.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class HttpUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpUtils.class);

    private HttpUtils() {
    }

    /**
     * Retrieve a representation by doing a GET on the specified URL.
     *
     * @param url     the URL
     * @param timeout the connect / read timeout value in milliseconds
     * @return the response content
     * @throws IOException if the request fails
     */
    public static String doGet(String url, int timeout) throws IOException {
        String content = null;
        if (isNotBlank(url)) {
            URLConnection urlConnection = new URL(url).openConnection();
            if (urlConnection instanceof HttpURLConnection) {
                HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;
                urlConnection.setConnectTimeout(timeout);
                urlConnection.setReadTimeout(timeout);
                try (InputStream inputStream = httpURLConnection.getInputStream()) {
                    content = copyToString(inputStream, UTF_8);
                    logger.info("The response of the metadata endpoint[URI : '{}'] : {}", url, content);
                } finally {
                    httpURLConnection.disconnect();
                }
            }
        }
        return content;
    }

    static String copyToString(InputStream inputStream, Charset charset) throws IOException {
        return new String(inputStream.readAllBytes(), charset);
    }
}
