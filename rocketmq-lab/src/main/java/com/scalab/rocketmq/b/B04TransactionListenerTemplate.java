package com.scalab.rocketmq.b;

import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.function.Function;

/**
 * B04 — 自定义 TransactionListener 模板：将 executeLocalTransaction + checkLocalTransaction
 * 抽象为模板，业务只需提供两个 Function。
 *
 * <p>对应 D6 §二 事务消息机制 + A06 原始实现。</p>
 */
public class B04TransactionListenerTemplate implements TransactionListener {

    private final Function<Message, LocalTransactionState> executor;
    private final Function<MessageExt, LocalTransactionState> checker;

    public B04TransactionListenerTemplate(Function<Message, LocalTransactionState> executor,
                                          Function<MessageExt, LocalTransactionState> checker) {
        this.executor = executor;
        this.checker = checker;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return executor.apply(msg);
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        return checker.apply(msg);
    }

    // ===== 预置策略 =====

    /** 永远 COMMIT */
    public static B04TransactionListenerTemplate alwaysCommit() {
        return new B04TransactionListenerTemplate(
                msg -> LocalTransactionState.COMMIT_MESSAGE,
                msg -> LocalTransactionState.COMMIT_MESSAGE);
    }

    /** 永远 ROLLBACK */
    public static B04TransactionListenerTemplate alwaysRollback() {
        return new B04TransactionListenerTemplate(
                msg -> LocalTransactionState.ROLLBACK_MESSAGE,
                msg -> LocalTransactionState.ROLLBACK_MESSAGE);
    }

    /** 按消息属性 "action" 决定：COMMIT→COMMIT，否则→ROLLBACK */
    public static B04TransactionListenerTemplate byProperty() {
        return new B04TransactionListenerTemplate(
                msg -> "COMMIT".equals(msg.getProperty("action"))
                        ? LocalTransactionState.COMMIT_MESSAGE
                        : LocalTransactionState.ROLLBACK_MESSAGE,
                msg -> "COMMIT".equals(msg.getProperty("action"))
                        ? LocalTransactionState.COMMIT_MESSAGE
                        : LocalTransactionState.ROLLBACK_MESSAGE);
    }
}
