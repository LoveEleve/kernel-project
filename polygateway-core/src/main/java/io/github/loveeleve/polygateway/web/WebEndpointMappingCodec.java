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
package io.github.loveeleve.polygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The JSON codec of {@link WebEndpointMapping}, the cross process metadata protocol between the
 * provider and the gateway.
 *
 * <p>A list of the mappings is encoded into a JSON array and attached into the service instance
 * metadata by the provider; the gateway decodes it back. The protocol is schema free : the field
 * names of this codec are the contract, both sides use the same class.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   String json = WebEndpointMappingCodec.encode(List.of(mapping));
 *   List<WebEndpointMapping> mappings = WebEndpointMappingCodec.decode(json);
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class WebEndpointMappingCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String KIND = "kind";

    private static final String ID = "id";

    private static final String NEGATED = "negated";

    private static final String PATTERNS = "patterns";

    private static final String METHODS = "methods";

    private static final String PARAMS = "params";

    private static final String HEADERS = "headers";

    private static final String CONSUMES = "consumes";

    private static final String PRODUCES = "produces";

    private WebEndpointMappingCodec() {
    }

    /**
     * Encodes the mappings into a JSON array.
     *
     * @param mappings the mappings
     * @return the encoded JSON
     */
    public static String encode(Collection<WebEndpointMapping> mappings) {
        ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
        for (WebEndpointMapping mapping : mappings) {
            arrayNode.add(encodeNode(mapping));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(arrayNode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes the mappings from a JSON array.
     *
     * @param json the encoded JSON
     * @return the decoded mappings
     */
    public static List<WebEndpointMapping> decode(String json) {
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            if (!arrayNode.isArray()) {
                throw new IllegalArgumentException("The web endpoint mappings JSON must be an array");
            }
            List<WebEndpointMapping> mappings = new ArrayList<>(arrayNode.size());
            for (JsonNode node : arrayNode) {
                mappings.add(decodeNode(node));
            }
            return mappings;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode encodeNode(WebEndpointMapping mapping) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put(KIND, mapping.getKind().name());
        node.put(ID, mapping.getId());
        node.put(NEGATED, mapping.isNegated());
        putArray(node, PATTERNS, mapping.getPatterns());
        putArray(node, METHODS, mapping.getMethods());
        putArray(node, PARAMS, mapping.getParams());
        putArray(node, HEADERS, mapping.getHeaders());
        putArray(node, CONSUMES, mapping.getConsumes());
        putArray(node, PRODUCES, mapping.getProduces());
        return node;
    }

    private static void putArray(ObjectNode node, String name, String[] values) {
        ArrayNode arrayNode = node.putArray(name);
        for (String value : values) {
            arrayNode.add(value);
        }
    }

    private static WebEndpointMapping decodeNode(JsonNode node) {
        return WebEndpointMapping.builder(WebEndpointMapping.Kind.valueOf(node.path(KIND).asText()))
                .id(node.path(ID).asInt())
                .negated(node.path(NEGATED).asBoolean())
                .patterns(stringArray(node.path(PATTERNS)))
                .methods(stringArray(node.path(METHODS)))
                .params(stringArray(node.path(PARAMS)))
                .headers(stringArray(node.path(HEADERS)))
                .consumes(stringArray(node.path(CONSUMES)))
                .produces(stringArray(node.path(PRODUCES)))
                .build();
    }

    private static String[] stringArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return new String[0];
        }
        String[] values = new String[arrayNode.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = arrayNode.get(i).asText();
        }
        return values;
    }
}
