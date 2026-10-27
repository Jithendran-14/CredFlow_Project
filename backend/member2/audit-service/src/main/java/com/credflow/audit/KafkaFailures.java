package com.credflow.audit;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
@Configuration public class KafkaFailures {
    @Bean DefaultErrorHandler errorHandler(KafkaTemplate<String,String> kafka) {
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,ex)->new TopicPartition(record.topic()+".DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(1000L,3L));
        handler.addNotRetryableExceptions(AuditEventProjector.InvalidAuditEventException.class);
        return handler;
    }
}
