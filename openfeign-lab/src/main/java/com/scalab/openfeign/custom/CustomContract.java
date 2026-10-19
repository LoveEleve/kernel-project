package com.scalab.openfeign.custom;

import feign.Contract;
import feign.MethodMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CustomContract implements Contract {

    private static final Logger log = LoggerFactory.getLogger(CustomContract.class);

    private final Contract delegate;

    public CustomContract(Contract delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
        log.debug("[CustomContract] Parsing: {}", targetType.getSimpleName());
        List<MethodMetadata> metadataList = delegate.parseAndValidateMetadata(targetType);
        for (MethodMetadata meta : metadataList) {
            log.debug("[CustomContract]   -> {} {}", meta.template().method(), meta.configKey());
        }
        return metadataList;
    }
}
