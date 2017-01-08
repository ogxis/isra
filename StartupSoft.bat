@echo off
java -Djava.library.path=lib/common/bin/ -cp "lib/common/jar/*" startup.StartupSoft config/startupSoftConfig.yml
pause
