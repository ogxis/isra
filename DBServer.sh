#!/bin/bash
#Start Database Server
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"

cd lib/orient*/bin
sh server.sh