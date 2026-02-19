# syntax=docker/dockerfile:1

FROM amazoncorretto:21-debian

# 이미지 빌드 시 실행되는 명령어
# 컨테이너 내부에 'appuser'라는 일반 사용자 생성, 기본 작업 디렉토리를 '/app'으로 설정
RUN useradd -ms /bin/bash appuser
WORKDIR /app

COPY build/libs/pooli.jar pooli.jar

# 8080 포트를 사용하는 컨테이너임을 명시(메타데이터 선언)
EXPOSE 8080
ENV JAVA_OPTS=""

# 컨테이너 시작 시 실행되는 최종 명령어 정의
# 'JAVA_OPTS' 환경변수 적용을 위해 'sh -c' 명령어 사용
USER appuser
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/pooli.jar"]