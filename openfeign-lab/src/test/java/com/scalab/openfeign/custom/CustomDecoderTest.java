package com.scalab.openfeign.custom;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class CustomDecoderTest {

    private final Response okResponse = createResponse(200, "hello");
    private final Response errorResponse = createResponse(500, "error");

    private static Response createResponse(int status, String body) {
        return Response.builder()
                .status(status)
                .reason(status >= 400 ? "error" : "ok")
                .headers(new LinkedHashMap<>())
                .body(body, java.nio.charset.StandardCharsets.UTF_8)
                .request(Request.create(Request.HttpMethod.GET, "http://localhost/test",
                        Collections.emptyMap(), null, new RequestTemplate()))
                .build();
    }

    @Test
    void shouldDelegateDecodeCall() throws Exception {
        Decoder delegate = (response, type) -> "decoded";
        CustomDecoder decoder = new CustomDecoder(delegate);

        Object result = decoder.decode(okResponse, String.class);

        assertEquals("decoded", result);
    }

    @Test
    void shouldPassTypeToDelegate() throws Exception {
        Type[] capturedType = new Type[1];
        Decoder delegate = (response, type) -> {
            capturedType[0] = type;
            return "ok";
        };
        CustomDecoder decoder = new CustomDecoder(delegate);

        decoder.decode(okResponse, String.class);

        assertEquals(String.class, capturedType[0]);
    }

    @Test
    void shouldPassResponseToDelegate() throws Exception {
        Response[] capturedResponse = new Response[1];
        Decoder delegate = (response, type) -> {
            capturedResponse[0] = response;
            return "ok";
        };
        CustomDecoder decoder = new CustomDecoder(delegate);

        decoder.decode(okResponse, Integer.class);

        assertEquals(200, capturedResponse[0].status());
    }

    @Test
    void shouldPropagateFeignException() {
        Decoder delegate = (response, type) -> {
            throw new FeignException.InternalServerError("server error",
                    response.request(), new byte[0],
                    response.headers());
        };
        CustomDecoder decoder = new CustomDecoder(delegate);

        assertThrows(FeignException.class,
                () -> decoder.decode(errorResponse, String.class));
    }

    @Test
    void shouldHandleNullResponse() {
        Decoder delegate = (response, type) -> null;
        CustomDecoder decoder = new CustomDecoder(delegate);

        assertDoesNotThrow(() -> {
            Object result = decoder.decode(okResponse, String.class);
            assertNull(result);
        });
    }

    @Test
    void shouldNotSwallowGenericException() {
        Decoder delegate = (response, type) -> {
            throw new RuntimeException("unexpected");
        };
        CustomDecoder decoder = new CustomDecoder(delegate);

        assertThrows(RuntimeException.class,
                () -> decoder.decode(okResponse, String.class));
    }
}
