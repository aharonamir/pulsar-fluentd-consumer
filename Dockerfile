FROM openjdk:8-jdk-slim

RUN mkdir -p /logs/conf
COPY fluentd/conf/log4j.properties /logs/conf/

RUN useradd -ms /bin/bash fluentd
COPY fluentd/conf/plugin-run.sh /home/fluentd/run.sh
RUN  chmod +x /home/fluentd/run.sh

USER fluentd
WORKDIR /home/fluentd

COPY build/libs/pulsar-fluentd-consumer-*.jar /home/fluentd/
COPY fluentd/conf/fluentd-consumer.properties /home/fluentd/fluentd-consumer.properties
RUN mv /home/fluentd/pulsar-fluentd-consumer-*.jar /home/fluentd/pulsar-fluentd-consumer.jar
#RUN echo networkaddress.cache.ttl=1 >> $JAVA_HOME/lib/security/java.security

WORKDIR /home/fluentd
ENTRYPOINT ["/home/fluentd/run.sh"]
