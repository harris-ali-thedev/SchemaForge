# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Optional public session CA. Temporary trust store is never included in the runtime image.
RUN --mount=type=secret,id=proxy_ca \
    set -eu; \
    maven_settings=""; \
    if [ -f /run/secrets/proxy_ca ]; then \
      cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts; \
      keytool -importcert -noprompt -alias session-proxy -file /run/secrets/proxy_ca -keystore /tmp/cacerts -storepass changeit; \
      export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/tmp/cacerts"; \
    fi; \
    proxy_url="${HTTPS_PROXY:-${https_proxy:-}}"; \
    if [ -n "$proxy_url" ]; then \
      proxy_address="${proxy_url#*://}"; proxy_address="${proxy_address%%/*}"; \
      proxy_host="${proxy_address%:*}"; proxy_port="${proxy_address##*:}"; \
      printf '<settings><proxies><proxy><id>build</id><active>true</active><protocol>http</protocol><host>%s</host><port>%s</port><nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy></proxies></settings>' "$proxy_host" "$proxy_port" > /tmp/settings.xml; \
      maven_settings="-s /tmp/settings.xml"; \
    fi; \
    mvn $maven_settings -B -ntp verify; \
    rm -f /tmp/cacerts /tmp/settings.xml
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/schemaforge-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
