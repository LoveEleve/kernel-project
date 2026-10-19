package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A06 事务消息配置——提供 TransactionMQProducer + RocketMQTemplate。
 */
@Configuration
public class A06TransactionConfig {

    private static final Logger log = LoggerFactory.getLogger(A06TransactionConfig.class);

    @Value("${rocketmq.name-server}")
    private String nameServer;

    public static final Map<String, String> txLog = new ConcurrentHashMap<>();

    /** 普通 Producer（不在此处 start，由 RocketMQTemplate 管理生命周期） */
    @Bean
    public DefaultMQProducer labProducer() {
        DefaultMQProducer producer = new DefaultMQProducer("rocketmq-lab-producer");
        producer.setNamesrvAddr(nameServer);
        return producer;
    }

    /** RocketMQTemplate（绑定 labProducer，覆盖 auto-config 空 template） */
    @Bean
    @Primary
    public RocketMQTemplate rocketMQTemplate(DefaultMQProducer labProducer) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(labProducer);
        return template;
    }

    /** 事务 Producer（独立 group，A06 专用） */
    @Bean(name = "a06TransactionProducer")
    public TransactionMQProducer a06TransactionProducer() {
        TransactionMQProducer producer = new TransactionMQProducer("a06-tx-group");
        producer.setNamesrvAddr(nameServer);
        producer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("[A06-TX] executeLocalTransaction: body={} arg={}", body, arg);
                txLog.put(body, "EXECUTED_OK");
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("[A06-TX] checkLocalTransaction: body={}", body);
                txLog.put(body, "CHECK_RETURN_COMMIT");
                return LocalTransactionState.COMMIT_MESSAGE;
            }
        });
        return producer;
    }
}
