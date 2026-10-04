FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache libstdc++

# Low-end defaults: cap the heap at half the container's RAM (the rest goes to
# native memory — OpenCV/ONNX matrices are off-heap), use SerialGC for the
# smallest native footprint, and exit on OOM instead of limping along.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

WORKDIR /app

COPY target/pad-liveness-backend-*.jar app.jar

# Run as an unprivileged user — the JVM extracts OpenCV natives and Haar
# cascades into /tmp, which stays writable for everyone.
RUN addgroup -S mosip && adduser -S -G mosip mosip \
    && chown -R mosip:mosip /app
USER mosip

EXPOSE 8000

CMD ["java", "-jar", "app.jar"]
