package com.scalab.openfeign.custom;

import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomErrorDecoderTest {

    private final ErrorDecoder delegate = (methodKey, response) -> new RuntimeException("delegate: " + response.status());

    private final CustomErrorDecoder decoder = new CustomErrorDecoder(delegate);

    private Response createResponse(int status, String body) {
        return Response.builder()
                .status(status)
                .reason(status >= 400 ? "error" : "ok")
                .headers(new LinkedHashMap<>())
                .body(body, java.nio.charset.StandardCharsets.UTF_8)
                .request(Request.create(Request.HttpMethod.GET, "http://localhost/test",
                        Collections.emptyMap(), null, new RequestTemplate()))
                .build();
    }

    private Response createResponseWithHeader(int status, String body, String headerName, String headerValue) {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put(headerName, List.of(headerValue));
        return Response.builder()
                .status(status)
                .reason(status >= 400 ? "error" : "ok")
                .headers(headers)
                .body(body, java.nio.charset.StandardCharsets.UTF_8)
                .request(Request.create(Request.HttpMethod.GET, "http://localhost/test",
                        Collections.emptyMap(), null, new RequestTemplate()))
                .build();
    }

    @Test
    void shouldReturnResourceNotFoundFor404() {
        Exception ex = decoder.decode("Test#method()", createResponse(404, "not found"));
        assertInstanceOf(CustomErrorDecoder.ResourceNotFoundException.class, ex);
        assertEquals(404, ((CustomErrorDecoder.ResourceNotFoundException) ex).getStatus());
    }

    @Test
    void shouldReturnRateLimitExceededFor429() {
        Exception ex = decoder.decode("Test#method()", createResponse(429, "too many requests"));
        assertInstanceOf(CustomErrorDecoder.RateLimitExceededException.class, ex);
        assertEquals(429, ((CustomErrorDecoder.RateLimitExceededException) ex).getStatus());
    }

    @Test
    void shouldReturnRetryableExceptionFor429WithRetryAfter() {
        ErrorDecoder realDefault = new ErrorDecoder.Default();
        CustomErrorDecoder decoderWithReal = new CustomErrorDecoder(realDefault);

        Exception ex = decoderWithReal.decode("Test#method()",
                createResponseWithHeader(429, "too many requests", "Retry-After", "5"));
        assertInstanceOf(RetryableException.class, ex);
    }

    @Test
    void shouldReturnServerErrorFor500() {
        Exception ex = decoder.decode("Test#method()", createResponse(500, "internal error"));
        assertInstanceOf(CustomErrorDecoder.ServerErrorException.class, ex);
        assertEquals(500, ((CustomErrorDecoder.ServerErrorException) ex).getStatus());
    }

    @Test
    void shouldReturnRetryableExceptionFor503WithRetryAfter() {
        ErrorDecoder realDefault = new ErrorDecoder.Default();
        CustomErrorDecoder decoderWithReal = new CustomErrorDecoder(realDefault);

        Exception ex = decoderWithReal.decode("Test#method()",
                createResponseWithHeader(503, "service unavailable", "Retry-After", "10"));
        assertInstanceOf(RetryableException.class, ex);
    }

    @Test
    void shouldDelegateForOtherStatusCodes() {
        Exception ex = decoder.decode("Test#method()", createResponse(400, "bad request"));
        assertInstanceOf(RuntimeException.class, ex);
        assertTrue(ex.getMessage().contains("delegate: 400"));
    }

    @Test
    void shouldPreserveBodyFor404() {
        CustomErrorDecoder.ResourceNotFoundException ex = assertThrows(
                CustomErrorDecoder.ResourceNotFoundException.class,
                () -> { throw decoder.decode("Test#method()", createResponse(404, "{\"error\":\"missing\"}")); });
        assertEquals("{\"error\":\"missing\"}", ex.getBody());
    }

    @Test
    void shouldAllowDelegateToReadBodyAfterCustomDecode() {
        ErrorDecoder trackingDelegate = (methodKey, response) -> {
            try {
                byte[] body = feign.Util.toByteArray(response.body().asInputStream());
                return new RuntimeException("delegate read " + body.length + " bytes");
            } catch (Exception e) {
                return new RuntimeException("delegate failed: " + e.getMessage());
            }
        };

        CustomErrorDecoder decoder = new CustomErrorDecoder(trackingDelegate);

        Exception ex = decoder.decode("Test#method()", createResponse(400, "readable body"));
        assertTrue(ex.getMessage().contains("delegate read"),
                "Delegate should be able to read the body after CustomErrorDecoder reads it. Got: " + ex.getMessage());
    }
}
