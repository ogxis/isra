---
layout: default
title: ICL
permalink: /how-it-works/icl/
---

# Identification and Classification Loop
* To identify and classify raw input / feedback data of any type.
* To check whether prediction matches reality (Prediction against Reality check, PaRc)

It gets patterns and data given by [solution tree]({% link _pages/how-it-works/implementation/decision-making.md %}#generate-solution-tree) and matches them against current latest raw input data.

---

## Default ICL (Analog ICL / Sensor ICL)
For most fundamental type of sensory input, pure analog signals with no additional encoding scheme, preferably with boundary range (max min upper lower bound).

### Requirements
* Must be analog signals (does not contain complex object type, only primitive type allowed).
* Can be converted to represent themselves in form of range 0~100 [polyVal]({% link _pages/how-it-works/glossary.md %}#poly-value).

### Implementation
* Initialize all new type of analog sensor input using provided data boundary.

* Clip them into polyVal range (0~100 double) alongside with a transition mapping function that translate equivalent analog data to equivalent polyVal representation.

* Begin to accept new raw data and for each of them no identification is required as they are pure analog data <span tooltip="As these are analog signals, no pattern is extractable from the signal as it is already in its pure form, thus it is valid
to consider it itself as the final pattern thus no more extraction is required (treat it as a whole). For example stepper
motor input, which is just an analog signal telling us its current position." class="tooltip"></span>.

* Treat those raw data as patterns.
* Forward those pattern to Working Memory via GCA to acknowledge the whole system about the existence of these newly arrived pattern data.

### Application
Any analog sensors, can be read only, writable or both.  
Example Read only:
* Temperature sensor
* Pressure sensor

Writable:
* Motors
* LEDs
* Relays

And any more analog based IO component.

---

## Visual ICL
### Requirements
* Produce the same output if fed with the same data given there is no external influence. (functional)
* Able to match pattern in scale invariant way.
* Able to match pattern extracted from the same frame.
* A controllable crude pattern/feature extraction algorithm.
* All input regardless of where it comes from (MPEG streams or individual images or anything else), must provide a way decode it into individual images storable inside a raw matrix.

### Implementation
* No initialization is required as the boundary of image data is dynamically deducible via the image encoding by reading the size of the matrix containing the decoded data.
* Clipping should already been done in code as image data are always going to be <span tooltip="0~255 is image RGB channel's range, it is fixed and have 256 possible state" class="tooltip">0~255 lower upper boundary</span>, thus the transition mapping function can be hard coded.

Begin to accept new raw image data and for each of them:
* If Working Memory has provided us with expected patterns, match those patterns against raw image
to see if they exist.
  * If exist, mark that region as recognized and record this pattern as an occurrence of the given pattern.
  * Else notify Working Memory that the specified pattern doesn't match anything (<span tooltip="Prediction against Reality check failure (PaRc12), a Working Memory operation." class="tooltip">PaRc fail</span>).

After depleting the given pattern or no pattern is given at all:
* For all remaining unrecognized (unprocessed) image area, run a find contour operation.
* For each contour found that are within unrecognized area, treat them as a new pattern.
* Forward those pattern to Working Memory via GCA to acknowledge the whole system about the
existence of these newly generated pattern data.

All these algorithm (pattern extraction, template matching <span tooltip="Scale invariant means the pattern image size can be bigger or smaller (scaled) but still being able to be recognized.
Rotation preferred to be treated as not match, therefore orientation is important." class="tooltip">scale invariant</span> , contour finding, contour
extraction and image decoder into matrix of 8 unsigned char 3 channel (8UC3) are all available in
the opencv library.

### Application
Any camera that uses any type of encoding can be used as long as it can be decoded to fill in an
image matrix.

---

## Audio ICL
### Requirements
* Produce the same output if fed with the same data given there is no external influence. (functional)
* Rudimentary pattern extraction based on any peak detection algorithm as long as the outputted pattern contains some form of isolation betweens data (eg. Silent point).
* Able to match pattern extracted from the same sample.
* All input regardless of where it comes from (WAVE streams or individual audio segment or anything else), must provide a way decode it into an pure audio waveform.

### Implementation
* No initialization is required as the boundary of audio data is dynamically deducible via the encoded audio header or directly counting the size of the data byte array.
* Clipping should already been done in code as audio data are always going to be <span tooltip="-1 ~ 1 double can already represent majority of possible sound state, if wanted to the range can be extended." class="tooltip">-1 ~ 1 float lower upper boundary</span>, thus the transition mapping function can be hard coded.

Begin to accept new raw audio data and for each of them:
* If Working Memory has provided us with expected patterns, match those patterns against raw audio data to see if they exist.
  * If exist, mark that region as recognized and record this pattern as an occurrence of the given pattern.
  * Else notify Working Memory that the specified pattern doesn't match anything (<span tooltip="Prediction against Reality check failure (PaRc), a Working Memory operation." class="tooltip">PaRc fail</span>).

After depleting the given pattern or no pattern is given at all:
* For all remaining unrecognized (unprocessed) area, run a general rudimentary pattern extraction algorithm which detects silent point or where peaks begin and end sequences in order to extract crudely meaningful pattern from the audio stream <span tooltip="For simplicity, a peak is considered more meaningful than silence or constant background noise by common sense.
Thus it qualifies as a breakpoint between interesting and not so meaningful data." class="tooltip"></span> .

* For each pattern extracted that are within unrecognized area, treat them as a new pattern.
* Forward those pattern to Working Memory via GCA to acknowledge the whole system about the existence of these newly generated pattern data.

No recommendation on any algorithm or library to use as there is no de facto industrial standard library when it comes to audio processing.

### Application
Any microphone or recording devices that uses any type of encoding can be used as long as it can be decoded into an audio stream.
