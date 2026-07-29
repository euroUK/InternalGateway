FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway-dsl-core/pom.xml gateway-dsl-core/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY scg-gateway/pom.xml scg-gateway/pom.xml
COPY services/deposit-offer-service/pom.xml services/deposit-offer-service/pom.xml
COPY services/processor-mock/pom.xml services/processor-mock/pom.xml
RUN mvn -pl scg-gateway -am dependency:go-offline -B

COPY gateway-dsl-core gateway-dsl-core
COPY scg-gateway scg-gateway
RUN mvn -pl scg-gateway -am package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/scg-gateway/target/scg-gateway-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
