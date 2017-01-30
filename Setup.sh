#!/bin/bash
#Setup initial required libraries and configurations for ISRA.
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"

echo Setting up ISRA required dependencies...

echo Deleting old lib/common/bin native library files...
rm -r lib/common/bin

echo Recreating lib/common/bin folder...
mkdir lib/common/bin

echo Copying platform specific native libraries into lib/common/bin ...

#http://stackoverflow.com/questions/106387/is-it-possible-to-detect-32-bit-vs-64-bit-in-a-bash-script
if [ `getconf LONG_BIT` = "64" ]
then
	echo "64bit operating system detected"
	echo "Uses 32bit library anyway..."
	cp -v lib/opencv/x86linux/* lib/common/bin
elif [ `getconf LONG_BIT` = "32" ]
then
	echo "32bit operating system detected"
	cp -v lib/opencv/x86linux/* lib/common/bin
else
	echo "Unknown Architecture, not x86 or x64"
fi

echo Begin downloading and installing third party dependencies...

#Basic compilation requirements.
sudo apt-get -y install gcc
sudo apt-get -y install make
sudo apt-get -y install python
#http://stackoverflow.com/questions/11094718/error-command-gcc-failed-with-exit-status-1-while-installing-eventlet
sudo apt-get -y install python-dev 

#Install pip to make setup.py work and install dependencies.
sudo apt-get -y install python-pip
   
echo "Download and installing dependencies required by dejavu library..."
   
#Following dependencies are all for dejavu
sudo apt-get -y install python-pyaudio
#http://stackoverflow.com/questions/26575587/cant-install-scipy-through-pip
#required for scipy, numpy.
sudo pip install cython
#Required for scipy to compile.
sudo apt-get -y install libatlas-base-dev gfortran

#Required for matplotlib to compile
#http://stackoverflow.com/questions/9829175/pip-install-matplotlib-error-with-virtualenv
sudo apt-get -y install pkg-config
sudo apt-get -y install libpng-dev
sudo apt-get -y install libfreetype6-dev

#Required for mysqldb to install properly.
#http://stackoverflow.com/questions/25865270/how-to-install-python-mysqldb-module-using-pip
sudo apt-get -y install libmysqlclient-dev

#http://stackoverflow.com/questions/7739645/install-mysql-on-ubuntu-without-password-prompt
export DEBIAN_FRONTEND=noninteractive
sudo -E apt-get -q -y install mysql-server
#http://unix.stackexchange.com/questions/21314/start-mysql-server-at-boot-for-debian
sudo update-rc.d mysql defaults
#Create database required by dejavu to store audio fingerprints.
mysql -u root -e "CREATE DATABASE IF NOT EXISTS dejavu;"

sudo pip install pydub
sudo pip install numpy
sudo pip install matplotlib
sudo pip install scipy
sudo pip install wavio
sudo pip install MySQL-python

#OR SIMPLER dejavu dependencies installations
#https://github.com/worldveil/dejavu/blob/master/INSTALLATION.md
sudo pip install PyDejavu

echo DONE
