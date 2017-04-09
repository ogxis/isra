---
layout: default
title: Decision Making
permalink: /how-it-works/decision-making/
---

# Decision Making
## Imitation
The system learns through **imitation**.  
Decision are **simply imitation of certain selected experience** and decide the steps involved to accomplish that decision.

Decision Steps:
* Selecting POI (Point of Interest)
* Generate Solution Tree (Plan ways to accomplish selected POI)
* Execute Solution Tree (Execute the plan and monitor result)

---

## POI Selection
POI Selection comprises of these deciding criteria:
* Relevance Against Global Inclination (Global Distribution)
* Experience Complexity (Depth of Experience)
* Central Tendency (Slight preference over neutral action when Global Distribution near both end)

### Whether to choose unique route
To ensure the system don't go into dead loop by encouraging it to pick on <span tooltip="Creatively generate new combination of decisions based on previous experiences relevant to current trend" class="tooltip">unique route</span> whenever possible to diversify learning experiences.  

If `globalDist > 25 && globalDist < 75` then prefer unique route.  
If prefer unique route, select route where <span tooltip="How many times the similar experiences is executed" class="tooltip">timeRan</span> is low.

### Relevance against global trend
To ensure selected POI is relevant to current <span tooltip="Relevance against global distribution value" class="tooltip">global trend</span>.

Do so by deducting the <span tooltip="Made of series of experiences chained together" class="tooltip">potential routes'</span> [polyVal]() against current Global Distribution.  
The less distance the better.

### Central Tendency
From the distance, if they are same, prefer the one that incline towards center.

### Operation Depth
Reorder again based on operation depth (steps).  
The higher the depth the better, as it shows the experience to be resimulated is much more <span tooltip="Abstract means the action has greater complexity (depth) within it. For example car 'abstract' away its internal complex actions behind the steering panel" class="tooltip">abstract</span>.

They must have equal timeRan in order to prove that they are high leveled but in <span tooltip="Have the same priority, but only one can continue" class="tooltip">conflict</span>, then check whether
they share the same depth, choose the one with the lowest depth as they are more unique than anything else,
a solution that is less popular than common mind and yet special enough to have so less timeRan.

---

## Generate Solution Tree
<span tooltip="POI is just another regular node with links to other node that can be accessed by traversing the links in between" class="tooltip">Traverse</span> from the selected POI, find the best path using the same decision logic AND viability.  
Viability can be determined from WM, by checking whether the particular requirement is possible to exist in the next step
by recursively checking it using previous experiences, compare and see if possible.

* If it is possible, then it will be preferred.
* If nothing is possible, it will take the best from the worst possible and make the leap of faint.

Short circuit decision making can be done by traversing only the possible route with limited depth, so it don't expand and
seek all possible route, although better route might exist there but instead speed up the decision process by selecting only
the best from few that come to mind first.  
If time allow, full expand will be best.

---

## Execute Solution Tree
Basically just execute all the action specified in the solution tree and compare whether similar patterns reoccurs (<span tooltip="Prediction against Reality check, compare prediction against reality (latest feedback input state)" class="tooltip">PaRc</span>).

* If prediction fails, switch to next branch of solution tree, then continue on execution.
* If all fails and no further solution available, generate a new tree with the same goal.

* If all the predictions goes well, he increase confidence on that particular subject.
* If it fails, he will be notified and forced to switch to another solution from the solution tree he just generated:
  * If it contains this unexpected situation, it means it is expected and he can just switch to that branch and execute.
  * Else he will have to generate a new tree as the no solution is available to solve that unexpected situation.

---

These processes will go on forever iteratively and it will create a mind system that is tolerant to unexpected situation with quick thinking to resolve it and get back on track to finish its task, able to accustom to any places as it doesn't requires any prerequisite to learn.

Experience are cross collate able thus can be reused on different occasions and as it is built up from scratch and pile up on experience.

With default inclination toward uniqueness (curiosity), it enable the system to be on constant learning phrase and promote creativity.

Together it allows ultimate generalization, abstract thinking, creativity and traits formulation to take place.
