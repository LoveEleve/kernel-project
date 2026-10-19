package com.scalab.openfeign.custom;

import feign.FeignException;
import feign.Response;
import feign.codec.Decoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;

public class CustomDecoder implements Decoder {

    private static final Logger log = LoggerFactory.getLogger(CustomDecoder.class);

    private final Decoder delegate;

    public CustomDecoder(Decoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException, FeignException {
        long start = System.nanoTime();
        log.debug("[CustomDecoder] decode: status={}, type={}", response.status(), type);

        try {
            Object result = delegate.decode(response, type);
            log.debug("[CustomDecoder]   result: {}, cost={}ns",
                    result != null ? result.getClass().getSimpleName() : "null", System.nanoTime() - start);
            return result;
        } catch (FeignException | IOException e) {
            log.warn("[CustomDecoder]   decode failed: {}", e.getMessage());
            throw e;
        }
    }
}
