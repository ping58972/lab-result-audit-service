FROM maven:3.9-eclipse-temurin-17
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml ./
COPY src/ src/
COPY tools/ tools/
RUN mvn -B -q -DskipTests package
CMD ["sh", "-c", "mvn -B test && python3 tools/smoke.py"]
