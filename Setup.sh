#!/bin/bash
#Setup initial required libraries and configurations for ISRA.
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"

echo Deleting old lib/common/bin native library files...
rm -r lib/common/bin

echo Recreating lib/common/bin folder...
mkdir lib/common/bin

echo Copying platform specific native libraries into lib/common/bin ...

#http://stackoverflow.com/questions/106387/is-it-possible-to-detect-32-bit-vs-64-bit-in-a-bash-script
if [ `getconf LONG_BIT` = "64" ]
then
	echo "64bit operating system detected"
	echo "Currently doesn't have x64 binary, compile it yourself."
elif [ `getconf LONG_BIT` = "32" ]
then
	echo "32bit operating system detected"
	cp -v lib/opencv/x86linux/* lib/common/bin
else
	echo "Unknown Architecture, not x86 or x64"
fi

echo DONE
