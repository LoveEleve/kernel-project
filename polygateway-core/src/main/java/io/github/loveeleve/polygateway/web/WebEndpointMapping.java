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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The mapping of one web endpoint of a provider application: the six dimensional conditions that
 * describe how the endpoint is reachable (path patterns, HTTP methods, request parameters,
 * headers, consumes and produces) plus the kind of the underlying web stack.
 *
 * <p>An instance of this class is serialized into the service instance metadata
 * ({@value io.github.loveeleve.polygateway.PolyGatewayConstants#WEB_ENDPOINTS_METADATA_NAME}) by the
 * provider side, so that the gateway discovers the endpoint without knowing the provider bean.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   WebEndpointMapping mapping = WebEndpointMapping.builder(Kind.MVC)
 *           .patterns("/orders/{id}")
 *           .methods("GET")
 *           .build();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public final class WebEndpointMapping implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The kind of the web stack that exposes the endpoint.
     */
    public enum Kind {

        /**
         * A Servlet / Spring MVC endpoint.
         */
        MVC,

        /**
         * A Spring WebFlux endpoint.
         */
        WEB_FLUX
    }

    /**
     * The kind of the endpoint.
     */
    private final Kind kind;

    /**
     * The stable identifier of the endpoint, used to build the route identifier on the gateway.
     */
    private final int id;

    /**
     * Whether the patterns of the endpoint are negated.
     */
    private final boolean negated;

    /**
     * The path patterns of the endpoint.
     */
    private final String[] patterns;

    /**
     * The HTTP methods of the endpoint, empty when all the methods are accepted.
     */
    private final String[] methods;

    /**
     * The request parameters of the endpoint, e.g. {@code name=value}.
     */
    private final String[] params;

    /**
     * The headers of the endpoint, e.g. {@code X-Api-Version=1}.
     */
    private final String[] headers;

    /**
     * The consumable media types of the endpoint.
     */
    private final String[] consumes;

    /**
     * The producible media types of the endpoint.
     */
    private final String[] produces;

    private WebEndpointMapping(Builder builder) {
        this.kind = Objects.requireNonNull(builder.kind, "The kind must not be null");
        this.negated = builder.negated;
        this.patterns = copy(builder.patterns);
        this.methods = copy(builder.methods);
        this.params = copy(builder.params);
        this.headers = copy(builder.headers);
        this.consumes = copy(builder.consumes);
        this.produces = copy(builder.produces);
        this.id = builder.id != null ? builder.id : stableId();
    }

    public Kind getKind() {
        return kind;
    }

    public int getId() {
        return id;
    }

    public boolean isNegated() {
        return negated;
    }

    public String[] getPatterns() {
        return clone(patterns);
    }

    public String[] getMethods() {
        return clone(methods);
    }

    public String[] getParams() {
        return clone(params);
    }

    public String[] getHeaders() {
        return clone(headers);
    }

    public String[] getConsumes() {
        return clone(consumes);
    }

    public String[] getProduces() {
        return clone(produces);
    }

    /**
     * @return the stable identifier computed from the six dimensional conditions
     */
    private int stableId() {
        int result = kind.hashCode();
        result = 31 * result + (negated ? 1 : 0);
        result = 31 * result + Arrays.hashCode(patterns);
        result = 31 * result + Arrays.hashCode(methods);
        result = 31 * result + Arrays.hashCode(params);
        result = 31 * result + Arrays.hashCode(headers);
        result = 31 * result + Arrays.hashCode(consumes);
        result = 31 * result + Arrays.hashCode(produces);
        return result;
    }

    private static String[] copy(String[] array) {
        return array == null ? new String[0] : array.clone();
    }

    private static String[] clone(String[] array) {
        return array.clone();
    }

    public static Builder builder(Kind kind) {
        return new Builder(kind);
    }

    /**
     * The builder of {@link WebEndpointMapping}.
     */
    public static class Builder {

        private final Kind kind;

        private Integer id;

        private boolean negated;

        private String[] patterns;

        private String[] methods;

        private String[] params;

        private String[] headers;

        private String[] consumes;

        private String[] produces;

        private Builder(Kind kind) {
            this.kind = kind;
        }

        public Builder id(int id) {
            this.id = id;
            return this;
        }

        public Builder negated(boolean negated) {
            this.negated = negated;
            return this;
        }

        public Builder patterns(String... patterns) {
            this.patterns = patterns;
            return this;
        }

        public Builder methods(String... methods) {
            this.methods = methods;
            return this;
        }

        public Builder params(String... params) {
            this.params = params;
            return this;
        }

        public Builder headers(String... headers) {
            this.headers = headers;
            return this;
        }

        public Builder consumes(String... consumes) {
            this.consumes = consumes;
            return this;
        }

        public Builder produces(String... produces) {
            this.produces = produces;
            return this;
        }

        public WebEndpointMapping build() {
            return new WebEndpointMapping(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebEndpointMapping)) {
            return false;
        }
        WebEndpointMapping that = (WebEndpointMapping) o;
        return kind == that.kind && negated == that.negated
                && Arrays.equals(patterns, that.patterns)
                && Arrays.equals(methods, that.methods)
                && Arrays.equals(params, that.params)
                && Arrays.equals(headers, that.headers)
                && Arrays.equals(consumes, that.consumes)
                && Arrays.equals(produces, that.produces);
    }

    @Override
    public int hashCode() {
        // The explicit id does not participate in the equality, keep the contract with the dims
        return stableId();
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "WebEndpointMapping{", "}");
        joiner.add("kind=" + kind);
        joiner.add("id=" + id);
        joiner.add("patterns=" + Arrays.toString(patterns));
        joiner.add("methods=" + Arrays.toString(methods));
        joiner.add("params=" + Arrays.toString(params));
        joiner.add("headers=" + Arrays.toString(headers));
        joiner.add("consumes=" + Arrays.toString(consumes));
        joiner.add("produces=" + Arrays.toString(produces));
        return joiner.toString();
    }
}
