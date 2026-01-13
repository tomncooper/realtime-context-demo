package com.smartship.streams.handlers;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.errors.ProductionExceptionHandler;
import org.apache.kafka.streams.errors.ErrorHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Custom production exception handler that logs errors and continues processing
 * instead of shutting down the Kafka Streams application.
 *
 * This handles exceptions that occur when trying to produce records to Kafka,
 * such as serialization failures due to Apicurio Registry connection issues.
 */
public class LogAndContinueProductionHandler implements ProductionExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LogAndContinueProductionHandler.class);

    @Override
    public ProductionExceptionHandlerResponse handle(ErrorHandlerContext context,
                                                      ProducerRecord<byte[], byte[]> record,
                                                      Exception exception) {
        LOG.error("Production failed - topic: {}, partition: {}. Skipping record and continuing.",
            record.topic(), record.partition(), exception);
        return ProductionExceptionHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // No configuration needed
    }
}
