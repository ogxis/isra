package isradatabase;

import com.tinkerpop.blueprints.impls.orient.OrientEdge;

import linkProperty.LinkProperty.LP;

/**
 * Graph basic storage element, edge.
 */
public class Edge {
	private OrientEdge oe;
	public Edge(OrientEdge givenOe) {
		oe = givenOe;
	}

	public <T> T getProperty(LP linkProperty) {
		return oe.getProperty(linkProperty.toString());
	}
	public void setProperty(LP linkProperty, Object property) {
		oe.setProperty(linkProperty.toString(), property);
	}
	public String getRid() {
		return oe.getIdentity().toString();
	}
	public void remove() {
		oe.remove();
	}
}