package com.scalab.openfeign.custom;

import feign.Capability;
import feign.Contract;
import feign.InvocationHandlerFactory;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

public class CustomCapability implements Capability {

    @Override
    public Contract enrich(Contract contract) {
        return new CustomContract(contract);
    }

    @Override
    public Encoder enrich(Encoder encoder) {
        return new CustomEncoder(encoder);
    }

    @Override
    public Decoder enrich(Decoder decoder) {
        return new CustomDecoder(decoder);
    }

    @Override
    public ErrorDecoder enrich(ErrorDecoder errorDecoder) {
        return new CustomErrorDecoder(errorDecoder);
    }

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory factory) {
        return new CustomInvocationHandlerFactory(factory);
    }
}
