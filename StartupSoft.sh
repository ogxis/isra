#!/bin/bash
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"
#This also set opencv native library path, else it will yield unsatisfied link error while running.
java -Djava.library.path=lib/common/bin/ -cp "lib/common/jar/*" startup.StartupSoft config/startupSoftConfig.yml
