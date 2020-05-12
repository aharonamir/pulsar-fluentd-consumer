package org.fluentd.pulsar.parser;

import java.util.Map;

import org.fluentd.pulsar.PropertyConfig;

public abstract class MessageParser {
    protected PropertyConfig config;

    public MessageParser(PropertyConfig config) {
        this.config = config;
    }

    public abstract Map<String, Object> parse(byte[] bytes) throws Exception;
}
