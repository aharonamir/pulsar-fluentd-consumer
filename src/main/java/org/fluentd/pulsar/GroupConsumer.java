package org.fluentd.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.komamitsu.fluency.Fluency;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GroupConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(GroupConsumer.class);

    PulsarClient client;
    private final List<Consumer> consumerList;
    private final PropertyConfig config;
    private ExecutorService executor;
    private final Fluency fluentLogger;

    public GroupConsumer(PropertyConfig config) throws IOException {
        this.config = config;

        int numThreads = config.getInt(PropertyConfig.Constants.FLUENTD_CONSUMER_THREADS.key,PropertyConfig.Constants.DEFAULT_CONSUMER_THREAD_POOL_SIZE);
        // create pulsar client
        client = PulsarClient.builder()
                .serviceUrl(config.get(PropertyConfig.Constants.PULSAR_SERVICE_URL.key,PropertyConfig.Constants.PUBLIC_DEFAULT_SERVICE_URL))
//                .ioThreads(numThreads)  - tuning
//                .listenerThreads(numThreads) - tuning
                .build();

        // create pulsar consumer
        this.consumerList = createPulsarConsumer(numThreads);
        // setup fluent logger
        this.fluentLogger = setupFluentdLogger();

    }

    private List<Consumer> createPulsarConsumer(int numThreads) throws PulsarClientException {
        String topics = config.get(PropertyConfig.Constants.FLUENTD_CONSUMER_TOPICS.key);
        List<Consumer> consumers = new ArrayList<>(numThreads);
        for (int  i = 0; i< numThreads; i++) {
            consumers.add(client.newConsumer()
                    .subscriptionName(config.get(PropertyConfig.Constants.PULSAR_CONSUMER_SUBSCRIPTION_NAME.key, PropertyConfig.Constants.PULSAR_DEFAULT_SUBSCRIPTION_NAME))
                    .subscriptionType(SubscriptionType.Shared) // enable for multi-instance
                    //.ackTimeout(10, TimeUnit.SECONDS) - tuning
                    .topicsPattern(topics)
                    .subscribe());
        }
        return consumers;
    }

    public Fluency setupFluentdLogger() throws IOException {
        Fluency.Config fConf = new Fluency.Config().setAckResponseMode(true).setMaxBufferSize(Long.valueOf(128 * 1024 * 1024L));
        try {
            fConf.setFileBackupDir(config.get(PropertyConfig.Constants.FLUENTD_CONSUMER_BACKUP_DIR.key));
        } catch (Exception e) {
            LOG.warn(PropertyConfig.Constants.FLUENTD_CONSUMER_BACKUP_DIR.key + " is not configured. Log lost may happen during shutdown if there are no active fluentd destinations");
        }

        // Set fluent logger buffer parameters
        String value = null;
        try {
            value = config.get(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_CHUNK_INITIAL.key);
            fConf.setBufferChunkInitialSize(Integer.valueOf(value));
        } catch (NumberFormatException e) {
            throw new RuntimeException(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_CHUNK_INITIAL.key + " parameter is wrong number format: " + value);
        } catch (RuntimeException e) {}
        try {
            value = config.get(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_CHUNK_RETENTION.key);
            //LOG.warn(Integer.valueOf(value));
            fConf.setBufferChunkRetentionSize(Integer.valueOf(value));
        } catch (NumberFormatException e) {
            //LOG.warn(e);
            throw new RuntimeException(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_CHUNK_RETENTION.key + " parameter is wrong number format: " + value);
        } catch (RuntimeException e) {}
        try {
            value = config.get(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_MAX.key);
            fConf.setMaxBufferSize(Long.valueOf(value));
        } catch (NumberFormatException e) {
            throw new RuntimeException(PropertyConfig.Constants.FLUENTD_CLIENT_BUFFER_MAX.key + " parameter is wrong number format: " + value);
        } catch (RuntimeException e) {}

        return Fluency.defaultFluency(config.getFluentdConnect(), fConf);
    }
 
    public void shutdown() {
        LOG.info("Shutting down consumers");

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3000, TimeUnit.MILLISECONDS)) {
                    LOG.error("Timed out waiting for consumer threads to shut down, exiting uncleanly");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOG.error("Interrupted during shutdown, exiting uncleanly");
                executor.shutdownNow();
            }
        }

        closeConsumers();
        closePulsarClient();
        closeFluentLogger();
    }

    private void closeFluentLogger() {
        try {
            fluentLogger.close();
            for (int i  =  0; i < 30; i++) {
                if (fluentLogger.isTerminated())
                    break;

                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException e) {}
            }
        } catch (IOException e) {
            LOG.error("failed to close fluentd logger completely", e);
        }
    }

    private void closePulsarClient() {
        if (client != null) {
            try {
                client.close();
            } catch (PulsarClientException e) {
                LOG.error("failed to close pulsar client", e);
            }
        }
    }

    private void closeConsumers() {
        if (consumerList != null) {
            try {
                for (final Consumer consumer : consumerList) consumer.close();
            } catch (PulsarClientException e) {
                LOG.error("failed to close pulsar consumer", e);
                e.printStackTrace();
            }
        }
    }

    public void run() {
        int numThreads = config.getInt(PropertyConfig.Constants.FLUENTD_CONSUMER_THREADS.key,PropertyConfig.Constants.DEFAULT_CONSUMER_THREAD_POOL_SIZE);

        executor = Executors.newFixedThreadPool(numThreads);
        // now create an object to consume the messages
        for (final Consumer consumer : consumerList) {
            executor.submit(new FluentdHandler(consumer, config, fluentLogger, executor));
        }
    }

    public static void main(String[] args) throws IOException {
        final PropertyConfig pc = new PropertyConfig(args[0]);
        final GroupConsumer gc = new GroupConsumer(pc);

        gc.run();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    gc.shutdown();
                }
            }));

        try {
            // Need better long running approach.
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(10000);
            }
        } catch (InterruptedException e) {
            LOG.error("Something happen!", e);
        }
    }
}
