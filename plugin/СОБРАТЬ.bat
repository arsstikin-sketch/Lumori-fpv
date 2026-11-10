@echo off
chcp 65001 >nul
echo Сборка плагина FpvDrone (нужен JDK 25 и JAVA_HOME)...
call gradlew.bat build
if errorlevel 1 (
  echo.
  echo ОШИБКА СБОРКИ. Пришли этот текст в чат.
  pause
  exit /b 1
)
copy /y build\libs\fpvdrone-plugin-*.jar . >nul
echo.
echo Готово! Файл fpvdrone-plugin-0.7.3.jar лежит в этой папке - положи его в папку plugins сервера.
pause
