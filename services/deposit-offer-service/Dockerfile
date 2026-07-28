FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway/pom.xml gateway/pom.xml
COPY services/deposit-offer-service/pom.xml services/deposit-offer-service/pom.xml
COPY services/processor-mock/pom.xml services/processor-mock/pom.xml
RUN mvn -pl services/deposit-offer-service -am dependency:go-offline -B

COPY services/deposit-offer-service services/deposit-offer-service
RUN mvn -pl services/deposit-offer-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
RUN mkdir -p /data
COPY --from=build /workspace/services/deposit-offer-service/target/deposit-offer-service-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
