/*
  Pass parameter from html
  http://bl.ocks.org/eyaler/10586116
  
  Parameters are:
  [0]div tag - to know where to anchor
  [1]json file - data to be displayed in graph
  [2]height of window - in pixel (OPTIONAL)
  [3]width of window - in pixel (OPTIONAL)
  
  Original source of graph:
  http://bl.ocks.org/eyaler/10586116
  https://gist.github.com/eyaler/10586116
  
  With additional features:
  -Multiple graph on same page
  -Display detail of selected node, description, previous next page navigation with node hiding.
*/
var GRAPHJS = GRAPHJS || (function() {
    var dataFileName = {}; // private
    return {
        init: function(ExternalArgs) {
            dataFileName = ExternalArgs[1];

            var divTag = ExternalArgs[0];
            var height = ExternalArgs[2];
            var width = ExternalArgs[3];
            //If user doesn't specify, use default layout width.
            if (width == null) {
                //Calculate the width of the body.
                //http://stackoverflow.com/questions/12524736/getting-width-of-an-element-defined-in-css-in-em
                width = window.innerWidth - (window.innerWidth - document.getElementById(divTag).offsetWidth);
            }
            if (height == null) {
                //Height default is full height, divide by 2 to look better.
                height = window.innerHeight / 2;
            }

            //Graph's parent div, the div that wraps all these element (graph, detail box, button, explaination texts)
            var graphDiv = document.getElementById(divTag);

            //Store explanation text for each stage. Div null as desired to append it after all is done.
            var explanationArr = null;
            var explanationParagraphDiv = null;

            //Used by graph's detail table. The div is parent of table.
            var table = null,
                tr = null,
                th = null,
                td = null;
            var detailTableDiv = null;

            //Current step of stage, to allow user to select step change.
            var currentStage = 0;
            var totalStage = null;


            var keyc = true,
                keys = true,
                keyt = true,
                keyr = true,
                keyx = true,
                keyd = true,
                keyl = true,
                keym = true,
                keyh = true,
                key1 = true,
                key2 = true,
                key3 = true,
                key0 = true

            var focus_node = null,
                highlight_node = null;

            var text_center = false;
            var outline = false;

            var min_score = 0;
            var max_score = 100;

            //Drag lock to disable drag on mobile device.
            var draglock = false;

            var color = d3.scale.linear()
                .domain([min_score, (min_score + max_score) / 2, max_score])
                .range(["lime", "yellow", "red"]);

            var highlight_color = "blue";
            var highlight_trans = 0.1;

            var size = d3.scale.pow().exponent(1)
                .domain([1, 100])
                .range([8, 24]);

            var force = d3.layout.force()
                .linkDistance(60)
                .charge(-300)
                .size([width, height]);

            var default_node_color = "#ccc";
            //var default_node_color = "rgb(3,190,100)";
            var default_link_color = "#888";
            var nominal_base_node_size = 8;
            var nominal_text_size = 10;
            var max_text_size = 24;
            var nominal_stroke = 1.5;
            var max_stroke = 4.5;
            var max_base_node_size = 36;
            var min_zoom = 0.1;
            var max_zoom = 7;
            //http://stackoverflow.com/questions/15573594/creating-a-border-around-your-d3-graph
            //Add border
            //divTag here must add # else it won't work.
            var svg = d3.select("#" + divTag).append("svg")
                .attr("style", "outline: thin solid blue;")
                .style("width", width + "px")
                .style("height", height + "px");

            var zoom = d3.behavior.zoom().scaleExtent([min_zoom, max_zoom])
            var g = svg.append("g");
            svg.style("cursor", "move");

            //The graph param is the actual json file data itself.
            d3.json(dataFileName, function(error, graph) {
                totalStage = graph.totalstageindex;
                explanationArr = graph.explanation[0];

                var linkedByIndex = {};
                graph.links.forEach(function(d) {
                    linkedByIndex[d.source + "," + d.target] = true;
                });

                //Store incoming and outgoing edge for each node as a string
                var incomingEdge = {};
                var outgoingEdge = {};

                //Precompute incoming and outgoing edges for simpler reference and display in detail window.
                //Duplicate link (same node, but different edgeType allowed.
                graph.links.forEach(function(d) {
                    var outgoing = outgoingEdge[d.source];
                    var incoming = incomingEdge[d.target];

                    var getData = function() {
                        return d.data == null ? ";" : ", data: " + d.data + "; ";
                    };

                    //id: nodeId, type: edgeType, data: data; 
                    var outgoingData = "id: " + graph.nodes[d.source].id + ", type: " + d.type + getData();
                    var incomingData = "id: " + graph.nodes[d.target].id + ", type: " + d.type + getData();

                    //If null then assign first, else append.
                    if (outgoing == null)
                        outgoingEdge[d.source] = outgoingData;
                    else
                        outgoingEdge[d.source] += outgoingData;

                    if (incoming == null)
                        incomingEdge[d.target] = incomingData;
                    else
                        incomingEdge[d.target] += incomingData;
                });

                //Append incoming, outgoing data into the main's node data field
                //http://stackoverflow.com/questions/3898563/appending-to-json-object-using-javascript
                //http://stackoverflow.com/questions/171251/how-can-i-merge-properties-of-two-javascript-objects-dynamically
                for (var i = 0; i < graph.nodes.length; i++) {
                    var d = graph.nodes[i];

                    if (incomingEdge[i] != null) {
                        if (d.data == null)
                            d.data = [{
                                "incoming": incomingEdge[i]
                            }];
                        else
                            jQuery.extend(d.data[0], {
                                "incoming": incomingEdge[i]
                            });
                    }
                    if (outgoingEdge[i] != null) {
                        if (d.data == null)
                            d.data = [{
                                "outgoing": outgoingEdge[i]
                            }];
                        else
                            jQuery.extend(d.data[0], {
                                "outgoing": outgoingEdge[i]
                            });
                    }
                }

                //Given a node and a link, check if they are connected.
                //By checking if either its source or target data (that data is index) equals to the node's index.
                //For unknown reason, the link's source were replaced by the actual source node data, means node == link both var
                //are same now automatically. For link, it also got replaced but with additional data.
                //One of those link's data is the original target index.
                function isConnectedLink(node, link) {
                    //link.source and link.target is invalid as it had been automatically replaced with real data.
                    if (link.target.index == node.index || link.source.index == node.index)
                        return true;
                    return false;
                }

                function isConnected(a, b) {
                    return linkedByIndex[a.index + "," + b.index] || linkedByIndex[b.index + "," + a.index] || a.index == b.index;
                }

                function hasConnections(a) {
                    for (var property in linkedByIndex) {
                        s = property.split(",");
                        if ((s[0] == a.index || s[1] == a.index) && linkedByIndex[property]) return true;
                    }
                    return false;
                }

                force
                    .nodes(graph.nodes)
                    .links(graph.links)
                    .start();

                var link = g.selectAll(".link")
                    .data(graph.links)
                    .enter().append("line")
                    .attr("class", "link")
                    .style("stroke-width", nominal_stroke)
                    .style("stroke", function(d) {
                        if (isNumber(d.score) && d.score >= 0) return color(d.score);
                        else return default_link_color;
                    })


                var node = g.selectAll(".node")
                    .data(graph.nodes)
                    .enter().append("g")
                    .attr("class", "node")

                    .call(force.drag)


                node.on("dblclick.zoom", function(d) {
                    d3.event.stopPropagation();
                    var dcx = (width / 2 - d.x * zoom.scale());
                    var dcy = (height / 2 - d.y * zoom.scale());
                    zoom.translate([dcx, dcy]);
                    g.attr("transform", "translate(" + dcx + "," + dcy + ")scale(" + zoom.scale() + ")");


                });


                var tocolor = "fill";
                var towhite = "stroke";
                if (outline) {
                    tocolor = "stroke"
                    towhite = "fill"
                }


                var circle = node.append("path")


                    .attr("d", d3.svg.symbol()
                        .size(function(d) {
                            return Math.PI * Math.pow(size(d.size) || nominal_base_node_size, 2);
                        })
                        .type(function(d) {
                            return d.type;
                        }))

                    .style(tocolor, function(d) {
                        if (isNumber(d.score) && d.score >= 0) return color(d.score);
                        else return default_node_color;
                    })
                    //.attr("r", function(d) { return size(d.size)||nominal_base_node_size; })
                    .style("stroke-width", nominal_stroke)
                    .style(towhite, "white");


                var text = g.selectAll(".text")
                    .data(graph.nodes)
                    .enter().append("text")
                    .attr("dy", ".35em")
                    .style("font-size", nominal_text_size + "px")

                if (text_center)
                    text.text(function(d) {
                        return d.id;
                    })
                    .style("text-anchor", "middle");
                else
                    text.attr("dx", function(d) {
                        return (size(d.size) || nominal_base_node_size);
                    })
                    .text(function(d) {
                        return '\u2002' + d.id;
                    });

                node.on("mouseover", function(d) {
                        set_highlight(d);
                    })
                    .on("mousedown", function(d) {
                        d3.event.stopPropagation();
                        focus_node = d;

                        if (highlight_node === null) set_highlight(d);

                        //Update the data to be displayed in detail window if data exist.

                        if (focus_node.data != null) {
                            //Draw node detail graph.
                            //Delete old detail graph
                            detailTableDiv.innerHTML = '';

                            //Convert to string, add the braces [], then convert to json again.
                            //convertedData = JSON.parse(  "["+JSON.stringify(focus_node.data)+"]"  );
                            convertedData = JSON.parse(JSON.stringify(focus_node.data));

                            //Append table under tableDiv.
                            detailTableDiv.appendChild(buildHtmlTable(focus_node.data));
                        }

                        //No data but previous detail graph exist, delete that graph.
                        else {
                            detailTableDiv.innerHTML = '';
                        }

                    }).on("mouseout", function(d) {
                        exit_highlight();
                    });

                d3.select("#" + divTag).on("mouseup",
                    function() {
                        if (focus_node !== null) {
                            focus_node = null;
                            if (highlight_trans < 1) {

                                circle.style("opacity", 1);
                                text.style("opacity", 1);
                                link.style("opacity", 1);
                            }
                        }

                        if (highlight_node === null) exit_highlight();
                    });

                function exit_highlight() {
                    highlight_node = null;
                    if (focus_node === null) {
                        svg.style("cursor", "move");
                        if (highlight_color != "white") {
                            circle.style(towhite, "white");
                            text.style("font-weight", "normal");
                            link.style("stroke", function(o) {
                                return (isNumber(o.score) && o.score >= 0) ? color(o.score) : default_link_color
                            });
                        }

                    }
                }

                function set_highlight(d) {
                    svg.style("cursor", "pointer");
                    if (focus_node !== null) d = focus_node;
                    highlight_node = d;

                    if (highlight_color != "white") {
                        circle.style(towhite, function(o) {
                            return isConnected(d, o) ? highlight_color : "white";
                        });
                        text.style("font-weight", function(o) {
                            return isConnected(d, o) ? "bold" : "normal";
                        });
                        link.style("stroke", function(o) {
                            return o.source.index == d.index || o.target.index == d.index ? highlight_color : ((isNumber(o.score) && o.score >= 0) ? color(o.score) : default_link_color);

                        });
                    }
                }


                zoom.on("zoom", function() {
                    if (!draglock) {
                        var stroke = nominal_stroke;
                        if (nominal_stroke * zoom.scale() > max_stroke) stroke = max_stroke / zoom.scale();
                        link.style("stroke-width", stroke);
                        circle.style("stroke-width", stroke);

                        var base_radius = nominal_base_node_size;
                        if (nominal_base_node_size * zoom.scale() > max_base_node_size) base_radius = max_base_node_size / zoom.scale();
                        circle.attr("d", d3.svg.symbol()
                            .size(function(d) {
                                return Math.PI * Math.pow(size(d.size) * base_radius / nominal_base_node_size || base_radius, 2);
                            })
                            .type(function(d) {
                                return d.type;
                            }))

                        //circle.attr("r", function(d) { return (size(d.size)*base_radius/nominal_base_node_size||base_radius); })
                        if (!text_center) text.attr("dx", function(d) {
                            return (size(d.size) * base_radius / nominal_base_node_size || base_radius);
                        });

                        var text_size = nominal_text_size;
                        if (nominal_text_size * zoom.scale() > max_text_size) text_size = max_text_size / zoom.scale();
                        text.style("font-size", text_size + "px");

                        g.attr("transform", "translate(" + d3.event.translate + ")scale(" + d3.event.scale + ")");
                    }
                });

                svg.call(zoom);

                resize();
                //window.focus();
                d3.select("#" + divTag).on("resize", resize).on("keydown", keydown);

                force.on("tick", function() {

                    node.attr("transform", function(d) {
                        return "translate(" + d.x + "," + d.y + ")";
                    });
                    text.attr("transform", function(d) {
                        return "translate(" + d.x + "," + d.y + ")";
                    });

                    link.attr("x1", function(d) {
                            return d.source.x;
                        })
                        .attr("y1", function(d) {
                            return d.source.y;
                        })
                        .attr("x2", function(d) {
                            return d.target.x;
                        })
                        .attr("y2", function(d) {
                            return d.target.y;
                        });

                    node.attr("cx", function(d) {
                            return d.x;
                        })
                        .attr("cy", function(d) {
                            return d.y;
                        });
                });

                function resize() {
                    var width2 = width,
                        height2 = height;
                    svg.attr("width", width2).attr("height", height2);

                    force.size([force.size()[0] + (width2 - width) / zoom.scale(), force.size()[1] + (height2 - height) / zoom.scale()]).resume();
                    width = width2;
                    height = height2;
                }

                function keydown() {
                    /*
	Disable keycode node hiding feature.
	
	if (d3.event.keyCode==32) {  force.stop();}
	else if (d3.event.keyCode>=48 && d3.event.keyCode<=90 && !d3.event.ctrlKey && !d3.event.altKey && !d3.event.metaKey)
	{
  switch (String.fromCharCode(d3.event.keyCode)) {
    case "C": keyc = !keyc; break;
    case "S": keys = !keys; break;
	case "T": keyt = !keyt; break;
	case "R": keyr = !keyr; break;
    case "X": keyx = !keyx; break;
	case "D": keyd = !keyd; break;
	case "L": keyl = !keyl; break;
	case "M": keym = !keym; break;
	case "H": keyh = !keyh; break;
	case "1": key1 = !key1; break;
	case "2": key2 = !key2; break;
	case "3": key3 = !key3; break;
	case "0": key0 = !key0; break;
  }
  	
  link.style("display", function(d) {
				var flag  = vis_by_type(d.source.type)&&vis_by_type(d.target.type)&&vis_by_node_score(d.source.score)&&vis_by_node_score(d.target.score)&&vis_by_link_score(d.score);
				linkedByIndex[d.source.index + "," + d.target.index] = flag;
              return flag?"inline":"none";});
  node.style("display", function(d) {
				return (key0||hasConnections(d))&&vis_by_type(d.type)&&vis_by_node_score(d.score)?"inline":"none";});
  text.style("display", function(d) {
                return (key0||hasConnections(d))&&vis_by_type(d.type)&&vis_by_node_score(d.score)?"inline":"none";});
				
				if (highlight_node !== null)
				{
					if ((key0||hasConnections(highlight_node))&&vis_by_type(highlight_node.type)&&vis_by_node_score(highlight_node.score)) { 
					if (focus_node!==null) set_focus(focus_node);
					set_highlight(highlight_node);
					}
					else {exit_highlight();}
				}

}	
   */

                }

                //Hide all the node that haven't reached its stage to show.
                node.filter(function(d) {

                        //To be hidden node, hide all of its concerned element as well.
                        if (d.stage > currentStage) {
                            text.style("visibility", function(o) {
                                return d.id == o.id ? "hidden" : "visible";
                            });
                            link.style("visibility", function(o) {
                                return isConnectedLink(d, o) ? "hidden" : "visible";
                            });
                            return true;
                        }
                        return false;
                    })
                    .style("visibility", "hidden");

                //Create data detail table to display selected node detail.          
                //http://stackoverflow.com/questions/5180382/convert-json-data-to-a-html-table
                function buildHtmlTable(arr) {
                    table = document.createElement('graph-table');
                    tr = document.createElement('tr');
                    th = document.createElement('th');
                    td = document.createElement('td');

                    var _table = table.cloneNode(false);
                    columns = addAllColumnHeaders(arr, _table);

                    for (var i = 0, maxi = arr.length; i < maxi; ++i) {
                        var _tr = tr.cloneNode(false);

                        for (var j = 0, maxj = columns.length; j < maxj; ++j) {
                            var _td = td.cloneNode(false);

                            cellValue = arr[i][columns[j]];
                            _td.appendChild(document.createTextNode(arr[i][columns[j]] || ''));
                            _tr.appendChild(_td);
                        }
                        _table.appendChild(_tr);
                    }
                    table = _table;
                    return _table;
                }

                // Adds a header row to the table and returns the set of columns.
                // Need to do union of keys from all records as some records may not contain
                // all records
                function addAllColumnHeaders(arr, table) {
                    var columnSet = [];
                    var _tr = tr.cloneNode(false);

                    for (var i = 0, l = arr.length; i < l; i++) {
                        for (var key in arr[i]) {
                            if (arr[i].hasOwnProperty(key) && columnSet.indexOf(key) === -1) {
                                columnSet.push(key);

                                var _th = th.cloneNode(false);

                                _th.appendChild(document.createTextNode(key));
                                _tr.appendChild(_th);
                            }
                        }
                    }
                    table.appendChild(_tr);
                    return columnSet;
                }

                //Create scrolllock button.
                //http://stackoverflow.com/questions/8650975/javascript-to-create-a-button-with-onclick
                var scrollLockButton = document.createElement("input");
                scrollLockButton.type = "button";
                scrollLockButton.value = "SCROLL";
                scrollLockButton.setAttribute("class", "graph-scroll-button");
                var scrolllock = false;

                //http://stackoverflow.com/questions/10592411/disable-scrolling-in-all-mobile-devices
                scrollLockButton.onclick = function() {
                    scrolllock = !scrolllock;
                    if (scrolllock) {
                        $("body").css("overflow-y", "hidden");
                        $("html").css("overflow-y", "hidden");
                        $(this).css('border-color', 'red');
                    } else {
                        $("body").css("overflow-y", "scroll");
                        $("html").css("overflow-y", "scroll");
                        $(this).css('border-color', 'green');
                    }
                };

                //Drag locking.
                var dragLockButton = document.createElement("input");
                dragLockButton.type = "button";
                dragLockButton.value = "DRAG";
                dragLockButton.setAttribute("class", "graph-drag-button");

                dragLockButton.onclick = function() {
                    draglock = !draglock;
                    if (draglock) {
                        $(this).css('border-color', 'red');
                    } else {
                        $(this).css('border-color', 'green');
                    }
                };

                graphDiv.appendChild(scrollLockButton);
                graphDiv.appendChild(dragLockButton);

                //Only create the button if there is more than 1 stage to display.
                if (totalStage > 0) {
                    //Previous and next graph button.
                    var previousStageButton = document.createElement("input");
                    previousStageButton.type = "button";
                    previousStageButton.value = "PREVIOUS";
                    previousStageButton.setAttribute("class", "graph-previous-button");

                    previousStageButton.onclick = function() {

                        if (currentStage - 1 >= 0) {
                            currentStage -= 1;

                            //Redraw to hide exposed elements.
                            //Set node to hidden if not reached its stage to display yet. Unspecified and reached will be displayed.
                            //http://stackoverflow.com/questions/20056370/how-to-show-and-hides-nodes-when-you-move-the-mouse-over-a-node-in-d3-javascript
                            node.filter(function(d) {

                                    //To be hidden node, hide all of its concerned element as well.
                                    if (d.stage > currentStage) {
                                        text.style("visibility", function(o) {
                                            return d.id == o.id ? "hidden" : "visible";
                                        });
                                        link.style("visibility", function(o) {
                                            return isConnectedLink(d, o) ? "hidden" : "visible";
                                        });
                                        return true;
                                    }

                                    //Update explanation text. Delete old then update.
                                    explanationParagraphDiv.innerHTML = '';
                                    explanationParagraphDiv.appendChild(document.createTextNode(graph.explanation[currentStage]));
                                    graphDiv.appendChild(explanationParagraphDiv);

                                    return false;
                                })
                                .style("visibility", "hidden");
                        }
                    };

                    var nextStageButton = document.createElement("input");
                    nextStageButton.type = "button";
                    nextStageButton.value = "NEXT";
                    nextStageButton.setAttribute("class", "graph-next-button");

                    nextStageButton.onclick = function() {

                        if (currentStage + 1 <= totalStage) {
                            currentStage += 1;

                            //Redraw to show hidden elements.
                            node.filter(function(d) {
                                    if (d.stage <= currentStage) {
                                        text.style("visibility", function(o) {
                                            //Do nothing if it doesn't match as we don't know its current state.
                                            return d.id == o.id ? "visible" : "";
                                        });
                                        link.style("visibility", function(o) {
                                            return isConnectedLink(d, o) ? "visible" : "";
                                        });


                                        return true;
                                    }
                                    return false
                                })
                                .style("visibility", "visible");
                        }

                        //Update explanation text. Delete old then update.
                        explanationParagraphDiv.innerHTML = '';
                        explanationParagraphDiv.appendChild(document.createTextNode(graph.explanation[currentStage]));
                        graphDiv.appendChild(explanationParagraphDiv);

                    };


                    graphDiv.appendChild(previousStageButton);
                    graphDiv.appendChild(nextStageButton);
                }

                //Create and display the default explanation text if available, this var is shared with buttons to dynamically update the text.
                var explanationParagraphDiv = document.createElement("div");
                explanationParagraphDiv.setAttribute("class", "graph-explanation-text");
                explanationParagraphDiv.appendChild(document.createTextNode(graph.explanation[currentStage]));
                graphDiv.appendChild(explanationParagraphDiv);

                //Create detail table div and anchor it below explanation div.
                detailTableDiv = document.createElement("div");
                detailTableDiv.setAttribute("class", "graph-table");
                graphDiv.appendChild(detailTableDiv);

                //End of scope of graph variable (the json data file)
            });

            function vis_by_type(type) {
                switch (type) {
                    case "circle":
                        return keyc;
                    case "square":
                        return keys;
                    case "triangle-up":
                        return keyt;
                    case "diamond":
                        return keyr;
                    case "cross":
                        return keyx;
                    case "triangle-down":
                        return keyd;
                    default:
                        return true;
                }
            }

            function vis_by_node_score(score) {
                if (isNumber(score)) {
                    if (score >= 0.666) return keyh;
                    else if (score >= 0.333) return keym;
                    else if (score >= 0) return keyl;
                }
                return true;
            }

            function vis_by_link_score(score) {
                if (isNumber(score)) {
                    if (score >= 0.666) return key3;
                    else if (score >= 0.333) return key2;
                    else if (score >= 0) return key1;
                }
                return true;
            }

            function isNumber(n) {
                return !isNaN(parseFloat(n)) && isFinite(n);
            }

        }
    };
}());
