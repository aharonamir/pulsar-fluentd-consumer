# Pulsar Consumer for Fluentd
This is a Java application for consuming data from Pulsar and forwarding to to Fluentd.
From fluentd you can forward to elasticsearch or other outputs.

This was forked from [treasure-data/kafka-fluentd-consumer](https://github.com/treasure-data/kafka-fluentd-consumer) and changed for [apache pulsar](https://pulsar.apache.org/)

## Build

Use gradle 2.1 or later.

    $ gradle shadowJar

## Run

### Run Pulsar
Start pulsar localy or from [docker](https://pulsar.apache.org/docs/en/standalone-docker/) and create topic/s
