package com.myxhs.dubbo.lab.naive;

import java.io.Serializable;

/**
 * 最简 RPC 响应：结果或异常，二选一。
 */
public class RpcResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object result;
    private Throwable error;

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }
}
