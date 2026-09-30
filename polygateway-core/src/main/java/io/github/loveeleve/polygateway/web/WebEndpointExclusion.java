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

import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.Objects;

/**
 * A rule that excludes the matching web endpoints from the generated routes. The rule is applied
 * when the gateway generates the endpoint routes, so an excluded endpoint gets no route at all.
 *
 * <p>The dimensions are combined with AND : every specified dimension must match the endpoint.
 * The matching semantics of each dimension :
 *
 * <ul>
 * <li>{@code patterns} : required, an endpoint pattern matches the rule when any endpoint pattern
 * matches any rule pattern (Ant style, e.g. {@code /internal/**})</li>
 * <li>{@code methods} : the endpoint is matched when the rule methods intersect the endpoint
 * methods; an endpoint without method condition accepts any method and is matched</li>
 * <li>{@code params} / {@code headers} : all the rule values must be declared by the endpoint</li>
 * <li>{@code consumes} / {@code produces} : the endpoint is matched when the media types
 * intersect</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   // Exclude every POST endpoint under /internal
 *   WebEndpointExclusion exclusion = new WebEndpointExclusion();
 *   exclusion.setPatterns("/internal/**");
 *   exclusion.setMethods("POST");
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class WebEndpointExclusion {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private String[] patterns;

    private String[] methods;

    private String[] params;

    private String[] headers;

    private String[] consumes;

    private String[] produces;

    /**
     * Tests whether the given endpoint is excluded by this rule.
     *
     * @param mapping the web endpoint
     * @return {@code true} when the endpoint must not be routed
     */
    public boolean matches(WebEndpointMapping mapping) {
        if (isEmpty(patterns)) {
            // Without patterns the rule would match everything, treat it as no rule
            return false;
        }
        return anyPatternMatches(mapping.getPatterns())
                && methodsMatch(mapping.getMethods())
                && declaredMatch(mapping.getParams(), params)
                && declaredMatch(mapping.getHeaders(), headers)
                && intersects(mapping.getConsumes(), consumes)
                && intersects(mapping.getProduces(), produces);
    }

    public String[] getPatterns() {
        return patterns;
    }

    public void setPatterns(String... patterns) {
        this.patterns = patterns;
    }

    public String[] getMethods() {
        return methods;
    }

    public void setMethods(String... methods) {
        this.methods = methods;
    }

    public String[] getParams() {
        return params;
    }

    public void setParams(String... params) {
        this.params = params;
    }

    public String[] getHeaders() {
        return headers;
    }

    public void setHeaders(String... headers) {
        this.headers = headers;
    }

    public String[] getConsumes() {
        return consumes;
    }

    public void setConsumes(String... consumes) {
        this.consumes = consumes;
    }

    public String[] getProduces() {
        return produces;
    }

    public void setProduces(String... produces) {
        this.produces = produces;
    }

    private boolean anyPatternMatches(String[] endpointPatterns) {
        for (String endpointPattern : endpointPatterns) {
            for (String rulePattern : patterns) {
                if (PATH_MATCHER.match(rulePattern, endpointPattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean methodsMatch(String[] endpointMethods) {
        if (isEmpty(methods)) {
            return true;
        }
        if (isEmpty(endpointMethods)) {
            // The endpoint accepts any method, including the excluded ones
            return true;
        }
        return intersects(endpointMethods, methods);
    }

    /**
     * The rule values must all be declared by the endpoint; an endpoint without the declared
     * dimension never matches the rule.
     */
    private boolean declaredMatch(String[] endpointValues, String[] ruleValues) {
        if (isEmpty(ruleValues)) {
            return true;
        }
        for (String ruleValue : ruleValues) {
            if (!contains(endpointValues, ruleValue)) {
                return false;
            }
        }
        return true;
    }

    private boolean intersects(String[] endpointValues, String[] ruleValues) {
        if (isEmpty(ruleValues)) {
            return true;
        }
        for (String ruleValue : ruleValues) {
            if (contains(endpointValues, ruleValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String[] values, String value) {
        return values != null && Arrays.stream(values).anyMatch(value::equals);
    }

    private static boolean isEmpty(String[] values) {
        return values == null || values.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebEndpointExclusion)) {
            return false;
        }
        WebEndpointExclusion that = (WebEndpointExclusion) o;
        return Arrays.equals(patterns, that.patterns)
                && Arrays.equals(methods, that.methods)
                && Arrays.equals(params, that.params)
                && Arrays.equals(headers, that.headers)
                && Arrays.equals(consumes, that.consumes)
                && Arrays.equals(produces, that.produces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(patterns), Arrays.hashCode(methods), Arrays.hashCode(params),
                Arrays.hashCode(headers), Arrays.hashCode(consumes), Arrays.hashCode(produces));
    }

    @Override
    public String toString() {
        return "WebEndpointExclusion{patterns=" + Arrays.toString(patterns)
                + ", methods=" + Arrays.toString(methods)
                + ", params=" + Arrays.toString(params)
                + ", headers=" + Arrays.toString(headers)
                + ", consumes=" + Arrays.toString(consumes)
                + ", produces=" + Arrays.toString(produces) + '}';
    }
}
