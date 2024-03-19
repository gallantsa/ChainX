package com.nageoffer.shortlink.project.config;

import com.nageoffer.shortlink.project.mq.consumer.ShortLinkStatsSaveConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nageoffer.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_STREAM_GROUP_KEY;
import static com.nageoffer.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_STREAM_TOPIC_KEY;

/**
 * Redis Stream 消息队列配置
 */
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfiguration {

    private final RedisConnectionFactory redisConnectionFactory;
    private final ShortLinkStatsSaveConsumer shortLinkStatsSaveConsumer;


    /**
     * 创建用于异步消息消费的线程池 Bean
     *
     * @return ExecutorService 实例
     */
    @Bean
    public ExecutorService asyncStreamConsumer() {
        // 用于生成线程名称的序号, 确保线程名称唯一
        AtomicInteger index = new AtomicInteger();
        // 获得当前机器的 CPU 核心数
        int processors = Runtime.getRuntime().availableProcessors();
        // 创建线程池, 这里没有设置拒绝策略, 使用默认的拒绝策略
        return new ThreadPoolExecutor(processors,
                processors + processors >> 1,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("stream_consumer_short-link_stats_" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    /**
     * 创建用于监听 Redis Stream 消息的容器 Bean
     *
     * @param asyncStreamConsumer 异步消息消费者线程池
     * @return StreamMessageListenerContainer 实例
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer(ExecutorService asyncStreamConsumer) {
        // 配置监听容器选项
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        // 一次最多获取多少条消息
                        .batchSize(10)
                        // 执行从 Stream 拉取到消息的任务流程
                        .executor(asyncStreamConsumer)
                        // 如果没有拉取到消息，需要阻塞的时间。不能大于 ${spring.data.redis.timeout}，否则会超时
                        .pollTimeout(Duration.ofSeconds(3))
                        .build();
        // 创建 StreamMessageListenerContainer
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // // 设置消息的接收规则
        streamMessageListenerContainer.receiveAutoAck(Consumer.from(SHORT_LINK_STATS_STREAM_GROUP_KEY, "stats-consumer"),
                StreamOffset.create(SHORT_LINK_STATS_STREAM_TOPIC_KEY, ReadOffset.lastConsumed()), shortLinkStatsSaveConsumer);
        return streamMessageListenerContainer;
    }
}