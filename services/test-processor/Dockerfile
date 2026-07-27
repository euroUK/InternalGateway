FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway/pom.xml gateway/pom.xml
COPY services/deposit-offer-service/pom.xml services/deposit-offer-service/pom.xml
COPY services/deposit-processor/pom.xml services/deposit-processor/pom.xml
COPY services/test-processor/pom.xml services/test-processor/pom.xml
RUN mvn -pl services/test-processor -am dependency:go-offline -B

COPY services/test-processor services/test-processor
RUN mvn -pl services/test-processor -am package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/services/test-processor/target/test-processor-*.jar app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
