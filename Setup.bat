@echo OFF

REM Setup initial required libraries and configurations for ISRA.
REM http://stackoverflow.com/questions/12322308/batch-file-to-check-64bit-or-32bit-os

reg Query "HKLM\Hardware\Description\System\CentralProcessor\0" | find /i "x86" > NUL && set OS=32BIT || set OS=64BIT

echo Setting up ISRA required dependencies...

echo Deleting old lib\common\bin native library files... 
@RD /S /Q "lib\common\bin"

echo Recreating lib\common\bin folder...
mkdir lib\common\bin

echo Copying platform specific native libraries into lib\common\bin ...

if %OS%==32BIT (
	echo 32bit operating system detected
	xcopy lib\opencv\x86win\* lib\common\bin
)
if %OS%==64BIT (
	echo 64bit operating system detected
	xcopy lib\opencv\x64win\* lib\common\bin
)



echo DONE

pause
