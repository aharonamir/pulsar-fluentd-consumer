# fluentd/Dockerfile
#FROM fluent/fluentd:v0.12-debian
#RUN ["gem", "install", "fluent-plugin-elasticsearch", "--no-rdoc", "--no-ri", "--version", "1.9.2"]
FROM fluent/fluentd:v1.10-debian-1
USER root
RUN ["fluent-gem", "install", "fluent-plugin-elasticsearch"]
USER fluent
