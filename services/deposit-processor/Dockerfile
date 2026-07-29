FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway-dsl-core/pom.xml gateway-dsl-core/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY scg-gateway/pom.xml scg-gateway/pom.xml
COPY services/deposit-offer-service/pom.xml services/deposit-offer-service/pom.xml
COPY services/processor-mock/pom.xml services/processor-mock/pom.xml
COPY services/deposit-processor/pom.xml services/deposit-processor/pom.xml
COPY services/test-processor/pom.xml services/test-processor/pom.xml
RUN mvn -pl services/deposit-processor -am dependency:go-offline -B

COPY services/deposit-processor services/deposit-processor
RUN mvn -pl services/deposit-processor -am package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/services/deposit-processor/target/deposit-processor-*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
