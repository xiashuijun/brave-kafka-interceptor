package no.sysco.middleware.kafka.interceptor.zipkin;

import brave.Span;
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer Interceptor to trace Records send to a Kafka Topic. It will extract context
 * from incoming Record, if exist injected in its header, and use it to link it to the
 * Span created by the interceptor.
 */
public class TracingProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

	static final Logger LOGGER = LoggerFactory.getLogger(TracingProducerInterceptor.class);

	static final String SPAN_NAME = "on_send";

	TracingConfiguration configuration;

	Tracing tracing;

	TraceContext.Injector<Headers> injector;

	TraceContext.Extractor<Headers> extractor;

	@Override
	public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
		TraceContextOrSamplingFlags traceContextOrSamplingFlags = extractor
				.extract(record.headers());
		Span span = tracing.tracer().nextSpan(traceContextOrSamplingFlags);
		tracing.propagation().keys().forEach(key -> record.headers().remove(key));
		injector.inject(span.context(), record.headers());
		if (!span.isNoop()) {
			if (record.key() instanceof String && !"".equals(record.key())) {
				span.tag(KafkaInterceptorTagKey.KAFKA_KEY, record.key().toString());
			}
			span.tag(KafkaInterceptorTagKey.KAFKA_TOPIC, record.topic())
					.tag(KafkaInterceptorTagKey.KAFKA_CLIENT_ID,
							configuration.getString(ProducerConfig.CLIENT_ID_CONFIG))
					.name(SPAN_NAME).kind(Span.Kind.PRODUCER).start();
		}
		span.finish();
		LOGGER.debug("Producer Record intercepted: {}", span.context());
		return record;
	}

	@Override
	public void onAcknowledgement(RecordMetadata recordMetadata, Exception exception) {
		// Do nothing
	}

	@Override
	public void close() {
		tracing.close();
	}

	@Override public void configure(Map<String, ?> configs) {
		configuration = new TracingConfiguration(configs);
		tracing = new TracingBuilder(configuration).build();
		extractor = tracing.propagation().extractor(KafkaInterceptorPropagation.HEADER_GETTER);
		injector = tracing.propagation().injector(KafkaInterceptorPropagation.HEADER_SETTER);
	}
}