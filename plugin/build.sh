#!/bin/sh
# Сборка на Linux/macOS: ./build.sh  (нужен JDK 25)
./gradlew build && cp build/libs/fpvdrone-plugin-*.jar . && echo "Готово: fpvdrone-plugin-0.7.3.jar"
