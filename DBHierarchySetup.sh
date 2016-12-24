#!/bin/bash
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"
java -jar ISRA.jar -0 remote:localhost ISRADB root 040B9006AD65107BCB968C890FC026620B0D8FD5CBF3796575EE80A1DC7C0826
