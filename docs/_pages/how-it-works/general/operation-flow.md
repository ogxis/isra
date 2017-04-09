---
layout: default
title: Operation Flow
permalink: /how-it-works/operation-flow/
---

# Flow
<div class="videowrapper">
    <img src="{% link /assets/israOverview.gif %}" alt="isra overview" />
</div>
If doesn't autoplay, [click here.](https://youtu.be/yhS_XC_39bg)

## Steps
Each layers runs on their own in a loop:  
1st Layer (Input and Classification System)
* Data comes in from external devices (eg camera, mic, any sensors)
* Data get identified and classified into groups and notify pattern sender whether their predictions come true.
* All data get pushed into working memory.

2nd Layer (Memory System)
* Get all data at particular moment and link them altogether into a snapshot.
* Phrase out outdated Working Memory.
* Maintain storage capacity.

3rd Layer (Decision Making and Execution System)
* Select new attention point (Point of Interest)
* From POI, generate solution tree.
* Execute the solution tree.

It learns by re-executing previous experience with or without additional modification (imitation).
A successful imitation will strengthen the links concerned and make future actions' result more predictable.
This is useful when chaining up layers of experience (The more it chains, the more unpredictable it will be, thus experience matters here).

POI selection is done by decision maker.
That step is called Select Attention Point.
POI are made using 3 points:
* [Relevance to current (Exist in current Working Memory)]()
* [Inclination towards center (Balancing)]()
* [Imitation Significance]()

Using [Global Distribution]() value, it ensure decision are made fairly, within threshold and alternating at best to avoid infinite loop.

Solution Tree are just compilation of experiences from how the selected POI was previously done.
It includes what data are present at the moment, now, the actions reactions and patterns as well.

Executing the tree means forward those data to appropriate sector that utilizes those data.
Expected patterns are forwarded to ICL (Originates from pattern recognized or created during that time).
Outputs are forwarded to external devices.

Internal state are matched internally by another internal ICL.
These steps essentially equals to prediction, it predicts what it will output.

Then it just keeps on repeat itself to continue the learning journey.

This is essential to chain future actions when actions are based on actions and the experiences matter to ensure it got the output it desired.
