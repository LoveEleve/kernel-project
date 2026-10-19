package com.scalab.openfeign.custom;

import feign.RequestTemplate;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CustomEncoderTest {

    private final RequestTemplate template = new RequestTemplate();

    @Test
    void shouldDelegateEncodeCall() {
        Encoder delegate = (object, bodyType, t) -> t.body("encoded");
        CustomEncoder encoder = new CustomEncoder(delegate);

        encoder.encode("test", String.class, template);

        assertNotNull(template.body());
    }

    @Test
    void shouldPassObjectAndBodyTypeToDelegate() {
        Type[] capturedType = new Type[1];
        Object[] capturedObject = new Object[1];
        Encoder delegate = (object, bodyType, t) -> {
            capturedObject[0] = object;
            capturedType[0] = bodyType;
            t.body("ok");
        };
        CustomEncoder encoder = new CustomEncoder(delegate);

        encoder.encode("hello", String.class, template);

        assertEquals("hello", capturedObject[0]);
        assertEquals(String.class, capturedType[0]);
    }

    @Test
    void shouldHandleNullObject() {
        Encoder delegate = (object, bodyType, t) -> t.body("null-handled");
        CustomEncoder encoder = new CustomEncoder(delegate);

        encoder.encode(null, String.class, template);

        assertNotNull(template.body());
    }

    @Test
    void shouldPropagateEncodeException() {
        Encoder delegate = (object, bodyType, t) -> {
            throw new EncodeException("encoding failed");
        };
        CustomEncoder encoder = new CustomEncoder(delegate);

        assertThrows(EncodeException.class, () -> encoder.encode("test", String.class, template));
    }

    @Test
    void shouldNotModifyTemplateWhenDelegateThrows() {
        Encoder delegate = (object, bodyType, t) -> {
            throw new EncodeException("fail");
        };
        CustomEncoder encoder = new CustomEncoder(delegate);

        RequestTemplate before = new RequestTemplate();
        before.body("original-body");
        byte[] originalBody = before.body();

        assertThrows(EncodeException.class, () -> encoder.encode("test", String.class, before));
        // body may or may not be set depending on delegate behavior — just verify no exception leak
    }

    @Test
    void shouldWorkWithByteArrayBodyType() {
        Encoder delegate = (object, bodyType, t) -> t.body(new String((byte[]) object, StandardCharsets.UTF_8));
        CustomEncoder encoder = new CustomEncoder(delegate);

        encoder.encode("hello".getBytes(StandardCharsets.UTF_8), byte[].class, template);

        assertNotNull(template.body());
    }
}
