#Used to prepare isra audio icl segment. Those raw data input comes in as segment, thus we must combine them into 1 large segment in order for the fingerpriting algorithm to have more data.

import os
import sys
from pydub import AudioSegment

if len(sys.argv) <=2:
	print("Usage: progName outputPathWithName varagsOfAudioFilePathsToBeConcatenated")
	sys.exit(1)

#https://github.com/jiaaro/pydub/blob/master/API.markdown
#Setup an empty audio object for concatenation purposes.
result = AudioSegment.empty()

for i in range(2, len(sys.argv)):
	result += AudioSegment.from_wav(sys.argv[i])

#Export the file then user will port that file to fingerprinting facility.
file_handle = result.export(sys.argv[1], format="wav")
sys.exit(0)
