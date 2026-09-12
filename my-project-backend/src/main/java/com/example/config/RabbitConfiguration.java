package com.example.config;

import com.example.utils.Const;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ消息队列配置
 */
@Configuration
public class RabbitConfiguration {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean("topicIndexMessageRecoverer")
    public RepublishMessageRecoverer topicIndexMessageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, "", Const.MQ_TOPIC_INDEX_ERROR);
    }

    @Bean("topicIndexRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory topicIndexRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Qualifier("topicIndexMessageRecoverer") MessageRecoverer messageRecoverer
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(100, 2.0, 200)
                .recoverer(messageRecoverer)
                .build());
        return factory;
    }

    @Bean("errorQueue")
    public Queue dlQueue() {
        return QueueBuilder
                .durable(Const.MQ_ERROR)
                .ttl(24 * 60 * 60 * 1000)
                .build();
    }

    @Bean("errorExchange")
    public Exchange dlExchange() {
        return ExchangeBuilder
                .directExchange("dlx.direct")
                .build();
    }

    @Bean
    public Binding dlBinding(@Qualifier("errorExchange") Exchange exchange,
                             @Qualifier("errorQueue") Queue queue) {
        return BindingBuilder
                .bind(queue)
                .to(exchange)
                .with("error-message")
                .noargs();
    }

    @Bean("mailQueue")
    public Queue queue(){
        return QueueBuilder
                .durable(Const.MQ_MAIL)
                .deadLetterExchange("dlx.direct")
                .deadLetterRoutingKey("error-message")
                .ttl(3 * 60 * 1000)
                .build();
    }

    @Bean("topicIndexErrorQueue")
    public Queue topicIndexErrorQueue() {
        return QueueBuilder.durable(Const.MQ_TOPIC_INDEX_ERROR).build();
    }

    @Bean("topicIndexQueue")
    public Queue topicIndexQueue() {
        return QueueBuilder.durable(Const.MQ_TOPIC_INDEX)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Const.MQ_TOPIC_INDEX_ERROR)
                .build();
    }

    @Bean("activityGrabMessageRecoverer")
    public RepublishMessageRecoverer activityGrabMessageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, "", Const.MQ_ACTIVITY_GRAB_ERROR);
    }

    @Bean("activityGrabRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory activityGrabRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Qualifier("activityGrabMessageRecoverer") MessageRecoverer messageRecoverer
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(100, 2.0, 200)
                .recoverer(messageRecoverer)
                .build());
        return factory;
    }

    @Bean("activityGrabErrorQueue")
    public Queue activityGrabErrorQueue() {
        return QueueBuilder.durable(Const.MQ_ACTIVITY_GRAB_ERROR).build();
    }

    @Bean("activityGrabQueue")
    public Queue activityGrabQueue() {
        return QueueBuilder.durable(Const.MQ_ACTIVITY_GRAB)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Const.MQ_ACTIVITY_GRAB_ERROR)
                .build();
    }
}
