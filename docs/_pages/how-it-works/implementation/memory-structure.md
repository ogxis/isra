---
layout: default
title: Memory Structure
permalink: /how-it-works/memory-structure/
---

# Work In Progress...

# Memory General Classification
Memory can be separated into 2 main type:  
Long Term Memory (LTM) and Short term memory (STM / Working Memory)

* Memory **once registered, are never destroyed**.
* Its significance depends on its link's weight.
* If it got recall frequently, it will have plenty of route we can retrieve it in the future, making it significant.

---

## Working Memory
A collection of memory where latest data (Sensor input, generated patterns, experiences) docks and decision made upon.

## Long term memory
**LTM is any data that got phrased out from working memory** as Working Memory spaces are limited.

Data that are either:
* <span tooltip="Too much data will slow down the decision making system as it seek through WM for new POI" class="tooltip">Too Old</span>.
* No Longer / Weakly referenced

are phrased out into LTM.

Data in both LTM and WM are same, WM just fetch data (reference) from LTM when required, and WM pushes data that no longer required into LTM.

---

# Graph Database
All the neural networks, data, experiences in ISRA are stored in [graph database](https://en.wikipedia.org/wiki/Graph_database).

Similar to how neuron works, graph data structure comprise of only 2 part:
* Vertex (node, similar to neuron)
* Edge (link, similar to neural pathways between neurons)

A simple graph


Special identifier are used to identify(tag) both vertexes and edges.
Several hardcoded fetching method are used to ensure data integrity and meaning.

They are:
Different fetching method yield different results.


All data with general vertex are mutually linkable, in order to allow new relationship to form.
Each link can be of type requirement or 

---

# General and Data Vertex
Majority of data in ISRA and separated into 2 part:
* General (Store mutual links among other general vertexes and context links to specific hardcoded vertexes)
* Data (Store real data and a link to its very own general vertex)

*** Graph

[You can find full list of all vertex edges type here.]({% link _pages/how-it-works/implementation/memory-structure-node.md %})
