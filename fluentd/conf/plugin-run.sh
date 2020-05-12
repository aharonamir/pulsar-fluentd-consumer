#!/bin/bash
java -Dlog4j.configuration=file:////logs/conf/log4j.properties -jar /home/fluentd/pulsar-fluentd-consumer.jar /home/fluentd/fluentd-consumer.properties
