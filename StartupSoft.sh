#!/bin/bash
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"
java -Djava.library.path=ext/ -jar ISRA.jar -2 config/startupSoftConfig.yml