package bank.internalgateway.gateway.messaging;

import bank.internalgateway.gateway.config.GatewayProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;

import java.lang.reflect.Method;
import java.util.List;

@Configuration
@EnableKafka
public class BindingKafkaListenerConfiguration implements KafkaListenerConfigurer {

    private final GatewayProperties properties;
    private final ConsumeBindingRegistry consumeBindingRegistry;
    private final InboundEventPipeline inboundEventPipeline;
    private final KafkaListenerContainerFactory<?> kafkaListenerContainerFactory;
    private final MessageHandlerMethodFactory messageHandlerMethodFactory;

    public BindingKafkaListenerConfiguration(
            GatewayProperties properties,
            ConsumeBindingRegistry consumeBindingRegistry,
            InboundEventPipeline inboundEventPipeline,
            KafkaListenerContainerFactory<?> kafkaListenerContainerFactory,
            BeanFactory beanFactory) {
        this.properties = properties;
        this.consumeBindingRegistry = consumeBindingRegistry;
        this.inboundEventPipeline = inboundEventPipeline;
        this.kafkaListenerContainerFactory = kafkaListenerContainerFactory;
        DefaultMessageHandlerMethodFactory factory = new DefaultMessageHandlerMethodFactory();
        factory.setBeanFactory(beanFactory);
        factory.afterPropertiesSet();
        this.messageHandlerMethodFactory = factory;
    }

    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        registrar.setMessageHandlerMethodFactory(messageHandlerMethodFactory);

        List<String> listenerBindings = properties.kafka() != null
                ? properties.kafka().listenerBindings()
                : List.of();
        if (listenerBindings == null || listenerBindings.isEmpty()) {
            throw new IllegalStateException("gateway.kafka.listener-bindings must contain at least one binding id");
        }

        for (String bindingId : listenerBindings) {
            MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
            endpoint.setId("kafka-listener-" + bindingId);
            endpoint.setGroupId(consumeBindingRegistry.kafkaConsumerGroup(bindingId));
            endpoint.setTopics(consumeBindingRegistry.kafkaTopic(bindingId));
            endpoint.setBean(new BindingKafkaListener(bindingId, inboundEventPipeline));
            endpoint.setMessageHandlerMethodFactory(messageHandlerMethodFactory);
            try {
                Method method = BindingKafkaListener.class.getMethod("onMessage", ConsumerRecord.class);
                endpoint.setMethod(method);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("Failed to configure Kafka listener for binding " + bindingId, ex);
            }
            registrar.registerEndpoint(endpoint, kafkaListenerContainerFactory);
        }
    }

    static final class BindingKafkaListener {

        private final String bindingId;
        private final InboundEventPipeline pipeline;

        BindingKafkaListener(String bindingId, InboundEventPipeline pipeline) {
            this.bindingId = bindingId;
            this.pipeline = pipeline;
        }

        public void onMessage(ConsumerRecord<String, String> record) throws Exception {
            pipeline.process(bindingId, record);
        }
    }
}
