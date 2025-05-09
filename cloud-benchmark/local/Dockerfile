FROM eclipse-temurin:21

LABEL org.opencontainers.image.source=https://github.com/xtdb/xtdb
LABEL org.opencontainers.image.description="XTDB Local Auctionmark"
LABEL org.opencontainers.image.licenses="MPL-2.0"

# Install unzip
RUN apt-get update -y && \
     apt-get upgrade -y && \
     apt-get dist-upgrade -y && \
     apt-get -y autoremove && \
     apt-get clean
RUN apt-get install unzip

# Fetch YourKit Java Profiler
RUN wget https://www.yourkit.com/download/docker/YourKit-JavaProfiler-2024.3-docker.zip -P /tmp/ && \
  unzip /tmp/YourKit-JavaProfiler-2024.3-docker.zip -d /usr/local && \
  rm /tmp/YourKit-JavaProfiler-2024.3-docker.zip

# Default Port for Yourkit
ENV YOURKIT_PORT=10001

# Expose metrics port
EXPOSE 8080

WORKDIR /usr/local/lib/xtdb

ENTRYPOINT ["java", \
    "-Dclojure.main.report=stderr", \
    "-Dlogback.configurationFile=logback.xml", \
    "--add-opens=java.base/java.nio=ALL-UNNAMED", \
    "-Dio.netty.tryReflectionSetAccessible=true", \
    "-Xmx2000m", "-Xms2000m", \
    "-XX:MaxDirectMemorySize=2000m", \
    "-XX:MaxMetaspaceSize=500m", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/var/lib/xtdb/heapdump", \
    # UNCOMMENT BELOW If you want to run a Yourkit agent for Remote Profiling
    "-agentpath:/usr/local/YourKit-JavaProfiler-2024.3/bin/linux-x86-64/libyjpagent.so=port=${YOURKIT_PORT},listen=all", \
    "-cp","xtdb-local-auctionmark.jar", \
    "clojure.main", "-m", "xtdb.run-auctionmark.main"]
    
HEALTHCHECK --start-period=15s --timeout=3s \
    CMD curl -f http://localhost:8080/healthz/alive || exit 1

RUN mkdir -p /var/lib/xtdb
VOLUME /var/lib/xtdb

ARG GIT_SHA
ARG XTDB_VERSION
ENV GIT_SHA=${GIT_SHA}
ENV XTDB_VERSION=${XTDB_VERSION}

LABEL org.opencontainers.image.version=${XTDB_VERSION}
LABEL org.opencontainers.image.revision=${GIT_SHA}

ADD local-config.yaml node-config.yaml
ADD local-config-kafka.yaml local-config-kafka.yaml
ADD build/libs/xtdb-local-auctionmark.jar xtdb-local-auctionmark.jar
