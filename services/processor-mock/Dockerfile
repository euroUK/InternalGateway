FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway/pom.xml gateway/pom.xml
COPY services/deposit-offer-service/pom.xml services/deposit-offer-service/pom.xml
COPY services/processor-mock/pom.xml services/processor-mock/pom.xml
RUN mvn -pl services/processor-mock -am dependency:go-offline -B

COPY services/processor-mock services/processor-mock
RUN mvn -pl services/processor-mock -am package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/services/processor-mock/target/processor-mock-*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
