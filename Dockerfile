FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY CatalogoMariaGomes.java .
COPY data ./data
COPY uploads ./uploads

RUN javac CatalogoMariaGomes.java

ENV PORT=8080

CMD ["java", "-cp", ".", "CatalogoMariaGomes"]
