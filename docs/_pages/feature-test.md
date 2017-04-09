---
layout: default
title: Feature TEST
permalink: /test/
---

# TEST PAGE
You should not be here.

# Test d3js here.
<!-- Draw a graph using provided json file -->
<!-- http://stackoverflow.com/questions/2190801/passing-parameters-to-javascript-files  -->
<div id="graph1" class="graph"><script type="text/javascript">GRAPHJS.init(["graph1", "{% link _data/test.json %}"]);</script></div>

<span tooltip="Tooltip Test" class="tooltip">Tooltip</span>

# Zoomable on hover image
<div class="easyzoom easyzoom--overlay">
  <a href="/assets/header.jpg">
    <img src="/assets/header.jpg" alt="" width="100%" height="100%" />
  </a>
</div>
