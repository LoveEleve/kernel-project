package com.scalab.openfeign.custom;

import feign.RequestTemplate;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;

public class CustomEncoder implements Encoder {

    private static final Logger log = LoggerFactory.getLogger(CustomEncoder.class);

    private final Encoder delegate;

    public CustomEncoder(Encoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) throws EncodeException {
        long start = System.nanoTime();
        log.debug("[CustomEncoder] encode: type={}, bodyType={}",
                object != null ? object.getClass().getSimpleName() : "null", bodyType);

        try {
            delegate.encode(object, bodyType, template);
            byte[] body = template.body();
            if (body != null) {
                log.debug("[CustomEncoder]   result: {} bytes, cost={}ns", body.length, System.nanoTime() - start);
            }
        } catch (EncodeException e) {
            log.warn("[CustomEncoder]   encode failed: {}", e.getMessage());
            throw e;
        }
    }
}
