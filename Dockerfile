FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy source
COPY ForgeServer.java .

# Download runtime dependencies
RUN curl -L -o sqlite-jdbc-3.51.2.0.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.51.2.0/sqlite-jdbc-3.51.2.0.jar \
    && curl -L -o json-20231013.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar \
    && curl -L -o postgresql-42.7.4.jar https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar

# Compile
RUN javac -cp ".:sqlite-jdbc-3.51.2.0.jar:json-20231013.jar:postgresql-42.7.4.jar" ForgeServer.java

EXPOSE 8080

CMD ["java", "-cp", ".:sqlite-jdbc-3.51.2.0.jar:json-20231013.jar:postgresql-42.7.4.jar", "ForgeServer"]
