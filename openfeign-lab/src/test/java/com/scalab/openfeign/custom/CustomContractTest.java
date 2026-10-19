package com.scalab.openfeign.custom;

import feign.Contract;
import feign.MethodMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomContractTest {

    @Test
    void shouldInvokeDelegate() {
        boolean[] invoked = {false};
        Contract delegate = targetType -> {
            invoked[0] = true;
            return List.of();
        };
        CustomContract contract = new CustomContract(delegate);
        contract.parseAndValidateMetadata(String.class);
        assertTrue(invoked[0]);
    }

    @Test
    void shouldPassTargetTypeToDelegate() {
        Class<?>[] capturedType = {null};
        Contract delegate = targetType -> {
            capturedType[0] = targetType;
            return List.of();
        };
        CustomContract contract = new CustomContract(delegate);
        contract.parseAndValidateMetadata(Integer.class);
        assertSame(Integer.class, capturedType[0]);
    }

    @Test
    void shouldReturnDelegatesResult() {
        Contract delegate = targetType -> List.of();
        CustomContract contract = new CustomContract(delegate);
        List<MethodMetadata> result = contract.parseAndValidateMetadata(String.class);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldPropagateDelegatesException() {
        Contract delegate = targetType -> {
            throw new IllegalArgumentException("parse failed");
        };
        CustomContract contract = new CustomContract(delegate);
        assertThrows(IllegalArgumentException.class,
                () -> contract.parseAndValidateMetadata(String.class));
    }
}
