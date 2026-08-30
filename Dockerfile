FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache libstdc++

WORKDIR /app

COPY target/pad-liveness-backend-*.jar app.jar

EXPOSE 8000

CMD ["java", "-jar", "app.jar"]
