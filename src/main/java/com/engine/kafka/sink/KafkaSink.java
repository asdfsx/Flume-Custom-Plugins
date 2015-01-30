package com.engine.kafka.sink;

import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import kafka.common.KafkaException;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import org.apache.flume.Channel;
import org.apache.flume.ChannelException;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.Sink.Status;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class KafkaSink extends AbstractSink implements Configurable {
	private static final Logger logger = LoggerFactory
			.getLogger(KafkaSink.class);
	private Properties clientProps;
	private SinkCounter sinkCounter;
	private String brokerlist;
	private String topic;
	private int batchsize;
	private ProducerConfig producerConfig;

	private Producer<String, String> producer;

	@Override
	public void configure(Context context) {
		clientProps = new Properties();

		brokerlist = context.getString("metadata.broker.list");
		topic = context.getString("topic");
		Preconditions.checkState(brokerlist != null, "No brokerlist specified");
		Preconditions.checkState(topic != null, "No topic specified");

		for (Entry<String, String> entry : context.getParameters().entrySet()) {
			clientProps.setProperty(entry.getKey(), entry.getValue());
			logger.info(
					"Kafka producer {} context: key:{}, value:{}",
					new Object[] { getName(), entry.getKey(), entry.getValue() });
		}

		producerConfig = new ProducerConfig(clientProps);
		batchsize = producerConfig.batchNumMessages();

		if (sinkCounter == null) {
			sinkCounter = new SinkCounter(getName());
		}
	}

	public void createProducer() {
		if (producer == null) {
			logger.info(
					"Kafka producer {}: Building Kafka Producer with brokerlist: {}, "
							+ "topic: {}", new Object[] { getName(),
							brokerlist, topic });

			producer = new Producer<String, String>(producerConfig);
			sinkCounter.incrementConnectionCreatedCount();
		}
	}

	public void destroyProducer() {
		if (producer != null) {
			logger.debug("Kafka sink {} closing Kafka producer: {}", getName(),
					producer);
			try {
				producer.close();
				sinkCounter.incrementConnectionClosedCount();
			} catch (KafkaException e) {
				sinkCounter.incrementConnectionFailedCount();
				logger.error("Kafka sink " + getName()
						+ ": Attempt to close Kafka "
						+ "producer failed. Exception follows.", e);
			}
		}

		producer = null;
	}

	private void verifyConnection() throws KafkaException {
		if (producer == null) {
			createProducer();
		}
	}

	private void resetProducer() {
		try {
			destroyProducer();
			createProducer();
		} catch (Throwable throwable) {
			// Don't rethrow, else this runnable won't get scheduled again.
			logger.error("Error while trying to expire connection", throwable);
		}
	}

	@Override
	public synchronized void start() {
		logger.info("Starting {}...", this);

		sinkCounter.start();
		try {
			createProducer();
		} catch (KafkaException e) {
			logger.warn("Unable to create Kafka producer using brokerlist: "
					+ brokerlist + ", topic: " + topic, e);

			/* Try to prevent leaking resources. */
			destroyProducer();
		}
		super.start();

		logger.info("Kafka sink {} started.", getName());
	}

	@Override
	public synchronized void stop() {
		logger.info("Kafka sink {} stopping...", getName());

		destroyProducer();
		sinkCounter.stop();
		super.stop();

		logger.info("Kafka sink {} stopped. Metrics: {}", getName(),
				sinkCounter);
	}

	@Override
	public String toString() {
		return "KafkaSink " + getName() + " { brokerlist: " + brokerlist
				+ ", topic: " + topic + " }";
	}

	public Status process() throws EventDeliveryException {
		Status status = Status.READY;
		Channel channel = getChannel();
		Transaction transaction = channel.getTransaction();

		try {
			transaction.begin();
			List<KeyedMessage<String, String>> batch = Lists.newLinkedList();

			for (int i = 0; i < batchsize; i++) {
				Event e = channel.take();
				if (e == null) {
					break;
				}
				String data = new String(e.getBody(), "utf-8");
				batch.add(new KeyedMessage<String, String>(topic, data));
			}

			int size = batch.size();
			
			if (size == 0) {
				sinkCounter.incrementBatchEmptyCount();
				status = Status.BACKOFF;
			} else {
				if (size < batchsize) {
					sinkCounter.incrementBatchUnderflowCount();
				} else {
					sinkCounter.incrementBatchCompleteCount();
				}
				sinkCounter.addToEventDrainAttemptCount(size);
				producer.send(batch);
			}
			
			logger.trace("Message size: " + batch.size());

			transaction.commit();
			sinkCounter.addToEventDrainSuccessCount(batchsize);
			return Status.READY;
		} catch (Throwable t) {
			transaction.rollback();
			if (t instanceof Error) {
				throw (Error) t;
			} else if (t instanceof ChannelException) {
				logger.error(
						"Kafka Sink " + getName()
								+ ": Unable to get event from" + " channel "
								+ channel.getName() + ". Exception follows.", t);
				status = Status.BACKOFF;
			} else {
				destroyProducer();
				throw new EventDeliveryException("Failed to send events", t);
			}
		} finally {
			transaction.close();
		}
		return status;
	}
}
