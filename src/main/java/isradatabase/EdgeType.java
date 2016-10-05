package isradatabase;

import com.tinkerpop.blueprints.impls.orient.OrientEdgeType;

import linkProperty.LinkProperty.LP;

/**
 * Edge's class representation. Used to configure its semantic.
 */
public class EdgeType {
	private OrientEdgeType oet;

	protected EdgeType(OrientEdgeType givenOet) {
		oet = givenOet;
	}

	public void createProperty(LP propertyName, PropertyType propertyType) {
		oet.createProperty(propertyName.toString(), propertyType.backendConvert());
	}
}