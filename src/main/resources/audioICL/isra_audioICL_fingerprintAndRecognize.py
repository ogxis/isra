#Our wrapper fingerprinting class for the main dejavu implementation, may add anymore optimization and output truncation here.
import os
import sys

#Use the default given dejavu lib function, calculate the fingerprint and recognize as needed specified by the command.

if len(sys.argv) !=4:
	print("Usage: progName [fingerprint/recognize] resetDatabase[true/false] [fileToFingerPrint/fileToRecognize]")
	sys.exit(1)

if sys.argv[2] == "true":
	#Drop the dejavu DB first and recreate it. Database is expected to not have any password requirement, and sql server should
	#already be online and running.
	os.system('mysql -u root -e "DROP DATABASE IF EXISTS dejavu; CREATE DATABASE dejavu;"')

#Change dir to dejavwu-master so the script can run correctly.
os.chdir('ext/dejavu-master/')

#Add ../../ prefix to the path, to compensate the dir changes above. So user code don't have to care about this or changing
#their own paths for each of the filePath given.
splittedFilePaths = sys.argv[3].split()
finalFilePath = ''

for i, s in enumerate(splittedFilePaths):
	finalFilePath += '../../'
	finalFilePath += splittedFilePaths[i]
	finalFilePath += ' '

#http://stackoverflow.com/questions/3781851/run-a-python-script-from-another-python-script-passing-in-args
#Run the default dejavu fingerprinting operation.
if sys.argv[1] == "fingerprint":
	os.system("python dejavu.py --fingerprint " + finalFilePath)
elif sys.argv[1] == "recognize":
	os.system("python dejavu.py --recognize file " + finalFilePath)
else:
	print("Unknown operation type: " + sys.argv[1] + ", expecting [fingerprint/recognize].")
	sys.exit(1)
sys.exit(0)
