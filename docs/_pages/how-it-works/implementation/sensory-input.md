---
layout: default
title: Sensory Input
permalink: /how-it-works/sensory-input/
---

# Sensory Input
Any **Device Sensors and Software are supported** given they **have interface** to deal with.

The **most basic sensor of all only have 2 stage, either on or off, true or false**.
Example of these are pressure touch sensor, audio streams, etc.

Sensors are mainly designed to mimic real senses.  
In the end, all of them will be converted to binary format for storage, thus if binary can represent all these data, our mind must be able to too.

---

## Flow
Sensor data are directly ported/made available for fetching to pattern processing layer for processing using [ICL]({% link _pages/how-it-works/implementation/icl.md %})<span tooltip="Identification and Classification Loop, a function loop" class="tooltip"></span>.

ICL will process all incoming raw sensory input and select whether to use specialized ICL depending on their type.

Visual and audio data are encoded in such a special format that it cannot be processed using the default ICL implementation designed for general analog based sensor input without <span tooltip="Data stored in visual and audio format are optimized and don't make sense without decoding when viewed from their binary form, unlike analog signals, where the data is in plain form" class="tooltip">significant effort</span><span tooltip="If uses default ICL, it learns directly from the binary stream as it cannot possibly be aware of it is encoded, thus the view of the data will be skrewed and learning progress will be slower" class="tooltip"></span>.

Therefore it must be provided with specialized ICL routines in order to extract and identify patterns from them separately, those algorithm are not required to be perfect, any open source recognition library can do the deal as long as they provide a <span tooltip="A function to compare whether 2 data are same or similar" class="tooltip">comparing function</span>.

---

## Rationale
Any data in existence can be has the properties of wave, and thus can be described in waveform
according to [quantum mechanics](https://www.youtube.com/watch?v=Io-HXZTepH4) <span tooltip="Quantum mechanics video: https://www.youtube.com/watch?v=Io-HXZTepH4 by Eugene Khutoryansky." class="tooltip"></span> .

No matter how complex it seems, a wave can be decomposed into multiple (or possibly infinite)
simple sine waves according to [fourier series](https://www.youtube.com/watch?v=r18Gi8lSkfM) <span tooltip="Fourier series video: https://www.youtube.com/watch?v=r18Gi8lSkfM by Eugene Khutoryansky.
Wiki: https://en.wikipedia.org/wiki/Fourier_series" class="tooltip"></span>.

Therefore for the universal solution it must possess the ability to ICL these basic waveform.
As the decomposed sine waves are always analog, it is set as default.

Visual and audio signals although in theory can be decomposed into simple sine waves and be
processed by the default ICL, doesn't do that due to:
* Complexity involved (decomposition processes not well defined nor understood) <span tooltip="How to separate an image into multiple waves that can at the same time represent particular spectrum while at the
same time exhibits its coordinate and compress these data into a single sine wave?
How and how not to separate audio signals so that the outputted signals remain significant/meaningful?" class="tooltip"></span>
* Loss of meaning (data has to be decoded to bring out multitude of original meanings and intents) <span tooltip="Encoding scheme are designed to represent multiple aspect of the data in a binary stream, default ICL expects pure
analog signals, thus decoding has to be done before feeding into it and figure out whether or not to separate those
aspects to feed to one or more ICL." class="tooltip"></span>

Thus specialized decoding and ICL scheme will be defined for them independently to boost learning performance.
