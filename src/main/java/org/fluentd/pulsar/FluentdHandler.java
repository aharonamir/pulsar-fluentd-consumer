package org.fluentd.pulsar;

import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.text.SimpleDateFormat;
import java.math.BigInteger;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.komamitsu.fluency.Fluency;
import org.komamitsu.fluency.BufferFullException;
import org.fluentd.pulsar.parser.MessageParser;
import org.fluentd.pulsar.parser.JsonParser;
import org.fluentd.pulsar.parser.RegexpParser;

public class FluentdHandler implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FluentdHandler.class);

    private final PropertyConfig config;
    private final FluentdTagger tagger;
    private final Consumer consumer;
    private final Fluency logger;
    private final MessageParser parser;
    private final String timeField;
    private final SimpleDateFormat formatter;
    private final int batchSize;
    private final ExecutorService executor;

    public FluentdHandler(Consumer consumer, PropertyConfig config, Fluency logger, ExecutorService executor) {
        this.config = config;
        this.tagger = config.getTagger();
        this.logger = logger;
        this.parser = setupParser();
        this.executor = executor;
        this.consumer = consumer;
        this.timeField = config.get("fluentd.record.time.field", null); // like "published"
        this.formatter = setupTimeFormatter();
        this.batchSize = config.getInt(PropertyConfig.Constants.FLUENTD_CONSUMER_BATCH_SIZE.key, PropertyConfig.Constants.DEFAULT_BATCH_SIZE);
    }

    public void run() {
        Exception ex = null;

        while (!executor.isShutdown()) {
            try {
                Message msg = receiveMsg();
                if (msg != null) {
                    try {
                        final byte[] bytes = msg.getData();
                        String tag = tagger.generate(msg.getTopicName());
                        Map<String, Object> data = null;
                        long time = 0; // 0 means use logger's automatic time generation

                        try {
                            data = parser.parse(bytes);

                            if (timeField != null) {
                                try {
                                    time = formatter.parse((String)data.get(timeField)).getTime() / 1000;
                                } catch (Exception e) {
                                    LOG.warn("failed to parse event time. Use current time: " + e.getMessage());
                                }
                            }
                            emitEvent(tag, data, time);
                        } catch (BufferFullException bfe) {
                            LOG.error("fluentd logger reached buffer full. Wait 1 second for retry", bfe);

                            while (true) {
                                try {
                                    TimeUnit.SECONDS.sleep(1);
                                } catch (InterruptedException ie) {
                                    LOG.warn("Interrupted during sleep");
                                    Thread.currentThread().interrupt();
                                }

                                try {
                                    emitEvent(tag, data, time);
                                    LOG.info("Retry emit succeeded. Buffer full is resolved");
                                    break;
                                } catch (IOException e) {}

                                LOG.error("fluentd logger is still buffer full. Wait 1 second for next retry");
                            }
                        } catch (IOException e) {
                            ex = e;
                        } catch (Exception e) {
                            Map<String, Object> failedData = new HashMap<String, Object>();
                            failedData.put("message", new String(bytes, StandardCharsets.UTF_8));
                            try {
                                emitEvent("failed", failedData, 0);
                            } catch (IOException e2) {
                                ex = e2;
                            }
                        }

                        // it's streaming messages, not a batch mode
//                        numEvents++;
//                        if (numEvents > batchSize) {
//                            consumer.commitOffsets();
//                            numEvents = 0;
//                            break;
//                        }

                        if (ex != null) {
                            LOG.error("can't send logs to fluentd. Wait 1 second", ex);
                            ex = null;
                            try {
                                TimeUnit.SECONDS.sleep(1);
                            } catch (InterruptedException ie) {
                                LOG.warn("Interrupted during sleep");
                                Thread.currentThread().interrupt();
                            }
                        }
                    } finally {
                        consumer.acknowledge(msg);
                    }
                }
            } catch (PulsarClientException e){
                LOG.error("Got pulsar client exception",e);
            }
        }

    }

    private Message receiveMsg() {
        try {
            final Message msg = consumer.receive();
            return msg;
        } catch (PulsarClientException e) {
            LOG.error("Failed in receiving pulsar msg",e);
            e.printStackTrace();
        }
        return null;
    }

    private MessageParser setupParser()
    {
        String format = config.get("fluentd.record.format", "json");
        switch (format) {
        case "json":
            return new JsonParser(config);
        case "regexp":
            return new RegexpParser(config);
        default:
            throw new RuntimeException(format + " format is not supported");
        }
    }

    private SimpleDateFormat setupTimeFormatter() {
        if (timeField == null)
            return null;

        return new SimpleDateFormat(config.get("fluentd.record.time.pattern"));
    }

    private void emitEvent(String tag, Map<String, Object> data, long timestamp) throws IOException {
        try {
            if (timestamp == 0)
                logger.emit(tag, data);
            else
                logger.emit(tag, timestamp, data);
        } catch (IllegalArgumentException e) { // MessagePack can't serialize BigInteger larger than 2^64 - 1 so convert it to String
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof BigInteger)
                    entry.setValue(value.toString());
            }

            if (timestamp == 0)
                logger.emit(tag, data);
            else
                logger.emit(tag, timestamp, data);
        }
    }
}
