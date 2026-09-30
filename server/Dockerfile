# 중동중학교 전자칠판 데이터 서버 - server 폴더만 있으면 빌드 · 실행됩니다.
#   docker compose up -d --build     (server 폴더에서)

FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY src ./src
ADD https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar lib/json.jar
RUN mkdir classes \
 && javac -nowarn --release 17 -encoding UTF-8 -cp lib/json.jar -d classes $(find src -name '*.java') \
 && cd classes && jar xf ../lib/json.jar org && cd .. \
 && printf 'Main-Class: kr.classboard.server.ServerMain\n' > manifest.txt \
 && jar cfm jdms-server.jar manifest.txt -C classes .

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/jdms-server.jar /app/jdms-server.jar
ENV PORT=8080 DATA_DIR=/data TZ=Asia/Seoul
VOLUME ["/data"]
EXPOSE 8080
CMD ["java", "-Xmx256m", "-jar", "/app/jdms-server.jar"]
