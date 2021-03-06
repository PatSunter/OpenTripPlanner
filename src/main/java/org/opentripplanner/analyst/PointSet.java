package org.opentripplanner.analyst;

import com.bedatadriven.geojson.GeometryDeserializer;
import com.bedatadriven.geojson.GeometrySerializer;
import com.csvreader.CsvReader;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;

import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.services.GraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PointSets serve as destinations in web analyst one-to-many indicators. They
 * can also serve as origins in many-to-many indicators.
 * 
 * PointSets are one of the three main web analyst resources: Pointsets
 * Indicators TimeSurfaces
 */
public class PointSet implements Serializable{

	private static final long serialVersionUID = -8962916330731463238L;

	private static final Logger LOG = LoggerFactory.getLogger(PointSet.class);

	public String id;
	public String label;
	public String description;

	public Map<String, Category> categories = new ConcurrentHashMap<String, Category>();
	public int capacity = 0; // The total number of features this PointSet can
								// hold.
	/*
	 * Connects this population to vertices in a given Graph (map of graph ids
	 * to sample sets). Keeping as a graphId->sampleSet map to prevent
	 * duplication of pointset when used across multiple graphs
	 */
	private Map<String, SampleSet> samples = new ConcurrentHashMap<String, SampleSet>();

	
	/*
	 * Used to generate SampleSets on an as needed basis. 
	 */
	protected GraphService graphService;

	/*
	 * In a detailed Indicator, the time to reach each target, for each origin.
	 * Null in non-indicator pointsets.
	 */
	public int[][] times;

	/**
	 * The geometries of the features. Each Attribute must contain an array of
	 * magnitudes with the same length as this list.
	 */

	protected String[] ids;
	protected double[] lats;
	protected double[] lons;
	protected Polygon[] polygons;

	/**
	 * A base class for the various levels of the Analyst GeoJSON "structured"
	 * attributes.
	 */
	public static abstract class Structured implements Serializable{
		private static final long serialVersionUID = 1001662681953599754L;
		
		String id;
		String label;
		String description;
		Style style;
		
		public Structured(){
			this.id=null;
		}

		public Structured(String id) {
			this.id = id;
		}

		public Structured(Structured other) {
			this.id = other.id;
			this.label = other.label;
			this.style = other.style;
			this.description = other.description;
		}

		public void addStyle(String attribute, String value) {
			if (style == null) {
				style = new Style();
			}
			style.attributes.put(attribute, value);
		}
		
		public String getId(){
			return this.id;
		}
		
		public String getlabel(){
			return this.label;
		}
		
		public String getDescription(){
			return this.description;
		}
	}

	public static class Style {
		Map<String, String> attributes = new ConcurrentHashMap<String, String>();
	}

	public static class Category extends Structured implements Serializable {

		private static final long serialVersionUID = -1976567868590201059L;
		
		Map<String, Attribute> attributes = new ConcurrentHashMap<String, Attribute>();
		
		public Category(){
			// blank constructor for deserialization
			super();
		}

		public Category(String id) {
			super(id);
		}

		/** Deep copy constructor. */
		public Category(Category other) {
			super(other);
			for (String key : other.attributes.keySet()) {
				attributes.put(key, new Attribute(other.attributes.get(key)));
			}
		}

		public Category slice(int start, int end) {
			Category ret = new Category(this.id);
			ret.description = this.description;
			ret.label = this.label;
			ret.style = this.style;
			
			for(Entry<String,Attribute> attr : this.attributes.entrySet()){
				ret.attributes.put( attr.getKey(), attr.getValue().slice(start,end) );
			}
			
			return ret;
		}
		
		public Map<String, Attribute> getAttributes(){
			return this.attributes;
		}
	}

	/**
	 * The leaves of the OTPA structured properties, with one magnitude or
	 * cumulative curve per feature.
	 */
	public static class Attribute extends Structured implements Serializable{

		private static final long serialVersionUID = -6525345212775303158L;
		
		int[] magnitudes;
		Histogram[] histogram; // An array, one per origin. Length is 1 until we
								// support many-to-many.
		
		public Attribute(){
			//blank constructor for deserialization
			super();
		}

		/** Shallow copy constructor. */
		public Attribute(String id) {
			super(id);
		}

		public Attribute(Attribute other) {
			super(other);
			this.magnitudes = other.magnitudes;
			this.histogram = other.histogram;
		}
		
		public Attribute slice(int start, int end) {
			Attribute ret = new Attribute(this.id);
			ret.description = this.description;
			ret.label = this.label;
			ret.style = this.style;
			
			ret.magnitudes = new int[end-start];
			
			if(this.histogram!=null){
				ret.histogram = new Histogram[end-start];
			}
			
			int n=0;
			for(int i=start; i<end; i++){
				ret.magnitudes[n] = this.magnitudes[i];
				
				if(this.histogram!=null){
					ret.histogram[n] = this.histogram[i];
				}
			}
			
			return ret;
		}
		
		public int[] getMagnitudes(){
			return this.magnitudes;
		}
		
		public Histogram[] getHistogram(){
			return this.histogram;
		}
	}

	/**
	 * Holds the attributes for a single feature when it's being loaded from
	 * GeoJSON. Not used for the OTP internal representation, just during the
	 * loading step. TODO Should potentially be used in CSV loading for
	 * uniformity of methods
	 */
	public static class AttributeData implements Serializable {
		private static final long serialVersionUID = 8485179983326803500L;
		
		public String category;
		public String attribute;
		public int value;
		
		public AttributeData() {
			this(null,null,0);
		}

		public AttributeData(String category, String attribute, int value) {
			this.category = category;
			this.attribute = attribute;
			this.value = value;
		}
		
		public String toString(){
			return category+"."+attribute+":"+value;
		}
	}

	/**
	 * Rather than trying to load anything any everything, we stick to a strict
	 * format and rely on other tools to get the data into the correct format.
	 * This includes column headers in the category:subcategory:attribute format
	 * and coordinates in WGS84. Comments begin with a #.
	 */
	public static PointSet fromCsv(String filename) throws IOException {
		/* First, scan through the file to count lines and check for errors. */
		CsvReader reader = new CsvReader(filename, ',', Charset.forName("UTF8"));
		reader.readHeaders();
		int nCols = reader.getHeaderCount();
		while (reader.readRecord()) {
			if (reader.getColumnCount() != nCols) {
				LOG.error("CSV record {} has the wrong number of fields.", reader.getCurrentRecord());
				return null;
			}
		}
		// getCurrentRecord is zero-based and does not include headers or blank
		// lines.
		int nRecs = (int) reader.getCurrentRecord() + 1;
		reader.close();
		/* If we reached here, the file is entirely readable. Start over. */
		reader = new CsvReader(filename, ',', Charset.forName("UTF8"));
		PointSet ret = new PointSet(nRecs);
		reader.readHeaders();
		if (reader.getHeaderCount() != nCols) {
			LOG.error("Number of headers changed.");
			return null;
		}
		int latCol = -1;
		int lonCol = -1;

		Attribute[] attributes = new Attribute[nCols];
		for (int c = 0; c < nCols; c++) {
			String header = reader.getHeader(c);
			if (header.equalsIgnoreCase("lat") || header.equalsIgnoreCase("latitude")) {
				latCol = c;
			} else if (header.equalsIgnoreCase("lon") || header.equalsIgnoreCase("longitude")) {
				lonCol = c;
			} else {
				Attribute attr = ret.getAttributeForColumn(header);
				attributes[c] = attr;
			}
		}
		if (latCol < 0 || lonCol < 0) {
			LOG.error("CSV file did not contain a latitude or longitude column.");
			throw new IOException();
		}
		for (Attribute attr : attributes) {
			if (attr != null)
				attr.magnitudes = new int[nRecs];
		}
		ret.lats = new double[nRecs];
		ret.lons = new double[nRecs];
		while (reader.readRecord()) {
			int rec = (int) reader.getCurrentRecord();
			for (int c = 0; c < nCols; c++) {
				Attribute attr = attributes[c];
				if (attr == null)
					continue; // skip lat and lon columns
				int mag = Integer.parseInt(reader.get(c));
				attr.magnitudes[rec] = mag;
			}
			ret.lats[rec] = Double.parseDouble(reader.get(latCol));
			ret.lons[rec] = Double.parseDouble(reader.get(lonCol));
		}
		ret.capacity = nRecs;
		return ret;
	}

	public static PointSet fromGeoJson(String filename) {
		try {
			FileInputStream fis = new FileInputStream(filename);
			int n = validateGeoJson(fis);
			if (n < 0)
				return null;
			fis.getChannel().position(0); // rewind file
			return fromValidatedGeoJson(fis, n);
		} catch (FileNotFoundException ex) {
			LOG.error("GeoJSON file not found: {}", filename);
			return null;
		} catch (IOException ex) {
			LOG.error("I/O exception while reading GeoJSON file: {}", filename);
			return null;
		}
	}

	/**
	 * Examines a JSON stream to see if it matches the expected OTPA format.
	 * 
	 * @return the number of features in the collection if it's valid, or -1 if
	 *         it doesn't fit the OTPA format.
	 */
	public static int validateGeoJson(InputStream is) {
		int n = 0;
		JsonFactory f = new JsonFactory();
		try {
			JsonParser jp = f.createParser(is);
			JsonToken current = jp.nextToken();
			if (current != JsonToken.START_OBJECT) {
				LOG.error("Root of OTPA GeoJSON should be a JSON object.");
				return -1;
			}
			// Iterate over the key:value pairs in the top-level JSON object
			while (jp.nextToken() != JsonToken.END_OBJECT) {
				String key = jp.getCurrentName();
				current = jp.nextToken();
				if (key.equals("features")) {
					if (current != JsonToken.START_ARRAY) {
						LOG.error("Error: GeoJSON features are not in an array.");
						return -1;
					}
					// Iterate over the features in the array
					while (jp.nextToken() != JsonToken.END_ARRAY) {
						n += 1;
						jp.skipChildren();
					}
				} else {
					jp.skipChildren(); // ignore all other keys except features
				}
			}
			if (n == 0)
				return -1; // JSON has no features
			return n;
		} catch (Exception ex) {
			LOG.error("Exception while validating GeoJSON: {}", ex);
			return -1;
		}
	}

	/**
	 * Reads with a combination of streaming and tree-model to allow very large
	 * GeoJSON files. The JSON should be already validated, and you must pass in
	 * the maximum number of features from that validation step.
	 */
	private static PointSet fromValidatedGeoJson(InputStream is, int n) {
		JsonFactory f = new MappingJsonFactory();
		PointSet ret = new PointSet(n);
		int index = 0;
		try {
			JsonParser jp = f.createParser(is);
			JsonToken current = jp.nextToken();
			// Iterate over the key:value pairs in the top-level JSON object
			while (jp.nextToken() != JsonToken.END_OBJECT) {
				String key = jp.getCurrentName();
				current = jp.nextToken();
				if (key.equals("features")) {
					while (jp.nextToken() != JsonToken.END_ARRAY) {
						// Read the feature into a tree model, which moves
						// parser to its end.
						JsonNode feature = jp.readValueAsTree();
						ret.addFeature(feature, index++);
					}
				} else {
					jp.skipChildren(); // ignore all other keys except features
				}
			}
		} catch (Exception ex) {
			LOG.error("GeoJSON parsing failure.");
			return null;
		}
		return ret;
	}

	/**
	 * Add one GeoJSON feature to this PointSet from a Jackson node tree.
	 * com.bedatadriven.geojson only exposed its streaming Geometry parser as a
	 * public method. I made its tree parser public as well. Geotools also has a
	 * GeoJSON parser called GeometryJson (which OTP wraps in
	 * GeoJsonDeserializer) but it consumes straight text, not a Jackson model
	 * or streaming parser.
	 */
	private void addFeature(JsonNode feature, int index) {

		PointFeature feat = null;
		try {
			feat = PointFeature.fromJsonNode(feature);
		} catch (EmptyPolygonException e) {
			LOG.warn("Empty MultiPolygon, skipping.");
			return;
		} catch (UnsupportedGeometryException e) {
			LOG.warn(e.message);
			return;
		}

		if (feat == null) {
			return;
		}

		addFeature(feat, index);
	}

	/**
	 * Create a PointSet manually by defining capacity and calling
	 * addFeature(geom, data) repeatedly.
	 * 
	 * @param capacity
	 *            expected number of features to be added to this PointSet.
	 */
	public PointSet(int capacity) {
		this.capacity = capacity;
		ids = new String[capacity];
		lats = new double[capacity];
		lons = new double[capacity];
		polygons = new Polygon[capacity];
	}

	/**
<<<<<<< HEAD
	 * Adds a grpah service to allow for auto creation of SampleSets for a given
	 * graph
	 * 
	 * @param reference
	 *            to the application graph service
	 */

	public void setGraphService(GraphService graphService) {
		this.graphService = graphService;
	}

	/**
	 * gets a sample set for a given graph id -- requires graphservice to be set
	 * 
	 * @param a valid graph id
	 * @return sampleset for graph
	 */

	public SampleSet getSampleSet(String routerId) {
		if(this.graphService == null) 
			return null;
		
		if (this.samples.containsKey(routerId))
			return this.samples.get(routerId);
		Graph g = this.graphService.getGraph(routerId);
		
		return getSampleSet(g);
	}
	
	/** 
	 * gets a sample set for a graph object -- does not require graph service to be set 
	 * @param g a graph objects
	 * @return sampleset for graph
	 */
	
	public SampleSet getSampleSet(Graph g) {	
		if (g == null)
			return null;
		SampleSet sampleSet = new SampleSet(this, g.getSampleFactory());
		this.samples.put(g.getRouterId(), sampleSet);
		return sampleSet;
	}
	

	/**
	 * Add a single feature with a variable number of free-form attributes.
	 * Attribute data contains id value pairs, ids are in form "cat_id:attr_id".
	 * If the attributes and categories do not exist, they will be created.
	 * TODO: read explicit schema or infer it and validate attribute presence as
	 * they're read
	 * 
	 * @param geom
	 *            must be a Point, a Polygon, or a single-element MultiPolygon
	 */

	public int featureCount() {
		return ids.length;
	}

	public void addFeature(PointFeature feat, int index) {
		if (index >= capacity) {
			throw new AssertionError("Number of features seems to have grown since validation.");
		}

		polygons[index] = feat.getPolygon();
		lats[index] = feat.getLat();
		lons[index] = feat.getLon();

		ids[index] = feat.getId();

		for (AttributeData ad : feat.getAttributes()) {
			Attribute attr = getAttributeFor(ad.category, ad.attribute);

			if (attr == null)
				continue;

			if (attr.magnitudes == null)
				attr.magnitudes = new int[capacity];

			attr.magnitudes[index] = ad.value;

		}
	}

	public PointFeature getFeature(int index) {
		PointFeature ret = new PointFeature(ids[index]);

		if (polygons[index] != null) {
			try {
				ret.setGeom(polygons[index]);
			} catch (Exception e) {
				// The polygon is clean; this should never happen. We
				// could pass the exception up but that'd just make the calling
				// function deal with an exception that will never pop. So
				// we'll make the compiler happy by catching it here silently.
			}
		}

		// ret.setGeom, if it was called, will already set the lat and lon
		// properties. But since every item in this pointset is guaranteed
		// to have a lat/lon coordinate, we defer to it as more authoritative.
		ret.setLat(lats[index]);
		ret.setLon(lons[index]);

		for (Entry<String, Category> category : this.categories.entrySet()) {
			for (Entry<String, Attribute> attribute : category.getValue().attributes.entrySet()) {
				String catLabel = category.getKey();
				String attrLabel = attribute.getKey();
				int mag = attribute.getValue().magnitudes[index];

				ret.addAttribute(new AttributeData(catLabel, attrLabel, mag));
			}
		}

		return ret;
	}

	public void setLabel(String catId, String label) {
		setLabel(catId, null, label);
	}

	public void setLabel(String catId, String attrId, String label) {
		Category category = categories.get(catId);

		if (category == null)
			return;

		if (attrId != null) {
			Attribute attribute = category.attributes.get(attrId);

			if (attribute == null)
				return;

			attribute.label = label;
		} else
			category.label = label;
	}

	public void setStyle(String catId, String styleAttribute, String styleValue) {
		setStyle(catId, null, styleAttribute, styleValue);
	}

	public void setStyle(String catId, String attrId, String styleAttribute, String styleValue) {
		Category category = categories.get(catId);

		if (category == null)
			return;

		if (attrId != null) {
			Attribute attribute = category.attributes.get(attrId);

			if (attribute == null)
				return;

			attribute.addStyle(styleAttribute, styleValue);
		} else
			category.addStyle(styleAttribute, styleValue);
	}

	/**
	 * Gets the Category object for the given ID, creating it if it doesn't
	 * exist.
	 * 
	 * @param id
	 *            the id for the category alone, not the fully-specified
	 *            category:attribute.
	 * @return a Category with the given ID.
	 */
	public Category getCategoryForId(String id) {
		Category category = categories.get(id);
		if (category == null) {
			category = new Category(id);
			categories.put(id, category);
		}
		return category;
	}

	/**
	 * @heading in the form "schools:primary" (i.e. category:attribute,
	 *          currently supports two levels)
	 * @return null if there are too many levels or the attribute does not exist
	 *         and you did not ask to create it.
	 */
	public Attribute getAttributeForColumn(String heading) {
		String[] levels = heading.split(":", 2);
		// There will always be at least one field if heading is non-null.
		if (levels.length == 1) {
			return getAttributeFor("NONE", levels[0]);
		}
		return getAttributeFor(levels[0], levels[1]);
	}

	public Attribute getAttributeFor(String cat, String attr) {
		Category category = getCategoryForId(cat);
		Attribute attribute = category.attributes.get(attr);
		if (attribute == null) {
			attribute = new Attribute(attr);
			category.attributes.put(attr, attribute);
		}
		return attribute;
	}

	public void writeJson(OutputStream out) {
		writeJson(out, false);
	}

	/**
	 * Use the Jackson streaming API to output this as GeoJSON without creating
	 * another object. The Indicator is a column store, and is transposed WRT
	 * the JSON representation.
	 */
	public void writeJson(OutputStream out, Boolean forcePoints) {
		try {
			JsonFactory jsonFactory = new JsonFactory(); // ObjectMapper.getJsonFactory()
															// is better
			JsonGenerator jgen = jsonFactory.createGenerator(out);
			jgen.setCodec(new ObjectMapper());
			jgen.writeStartObject();
			{

				jgen.writeStringField("type", "FeatureCollection");

				jgen.writeObjectFieldStart("properties");
				{

					if (id != null)
						jgen.writeStringField("id", id);
					if (label != null)
						jgen.writeStringField("label", label);
					if (description != null)
						jgen.writeStringField("description", description);

					// writes schema as a flat namespace with cat_id and
					// cat_id:attr_id interleaved

					jgen.writeObjectFieldStart("schema");
					{

						for (Category cat : this.categories.values()) {

							jgen.writeObjectFieldStart(cat.id);
							{
								if (cat.label != null)
									jgen.writeStringField("label", cat.label);
								jgen.writeStringField("type", "Category");

								if (cat.style != null && cat.style.attributes != null) {

									jgen.writeObjectFieldStart("style");
									{

										for (String styleKey : cat.style.attributes.keySet()) {
											jgen.writeStringField(styleKey, cat.style.attributes.get(styleKey));
										}
									}
									jgen.writeEndObject();

								}

							}
							jgen.writeEndObject();

							for (Attribute attr : cat.attributes.values()) {

								jgen.writeObjectFieldStart(cat.id + ":" + attr.id);
								{
									if (attr.label != null)
										jgen.writeStringField("label", attr.label);
									jgen.writeStringField("type", "Attribute");

									if (attr.style != null && attr.style.attributes != null) {

										jgen.writeObjectFieldStart("style");
										{

											for (String styleKey : attr.style.attributes.keySet()) {
												jgen.writeStringField(styleKey, attr.style.attributes.get(styleKey));
											}
										}
										jgen.writeEndObject();

									}

								}
								jgen.writeEndObject();
							}

							// two-level hierarchy for now... could be extended
							// to recursively map
							// categories,sub-categories,attributes
						}

					}
					jgen.writeEndObject();
				}
				jgen.writeEndObject();

				jgen.writeArrayFieldStart("features");
				{
					for (int f = 0; f < capacity; f++) {
						writeFeature(f, jgen, forcePoints);
					}
				}
				jgen.writeEndArray();
			}
			jgen.writeEndObject();
			jgen.close();
		} catch (IOException ioex) {
			LOG.info("IOException, connection may have been closed while streaming JSON.");
		}
	}

	/**
	 * Pairs an array of times with the array of features in this pointset,
	 * writing out the resulting (ID,time) pairs to a JSON object.
	 */
	protected void writeTimes(JsonGenerator jgen, int[] times) throws IOException {
		jgen.writeObjectFieldStart("times");
		for (int i = 0; i < times.length; i++) { // capacity is now 1 if this is
													// a one-to-many indicator
			int t = times[i];
			if (t != Integer.MAX_VALUE)
				jgen.writeNumberField(ids[i], t);
		}
		jgen.writeEndObject();
	}

	/**
	 * This writes either a polygon or lat/lon point defining the feature. In
	 * the case of polygons, we convert these back to centroids on import, as
	 * OTPA depends on the actual point. The polygons are kept for derivative
	 * uses (e.g. visualization)
	 * 
	 * @param i
	 *            the feature index
	 * @param jgen
	 *            the Jackson streaming JSON generator to which the geometry
	 *            will be written
	 * @throws IOException
	 */
	private void writeFeature(int i, JsonGenerator jgen, Boolean forcePoints) throws IOException {

		GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel());

		GeometrySerializer geomSerializer = new GeometrySerializer();

		jgen.writeStartObject();
		{
			jgen.writeStringField("id", ids[i]);
			jgen.writeStringField("type", "Feature");
			jgen.writeFieldName("geometry");
			{

				if (!forcePoints && polygons != null && polygons.length >= i && polygons[i] != null) {
					geomSerializer.writeGeometry(jgen, polygons[i]);
				} else {

					Point p = geometryFactory.createPoint(new Coordinate(lons[i], lats[i]));
					geomSerializer.writeGeometry(jgen, p);
				}

			}
			jgen.writeObjectFieldStart("properties");
			{
				writeStructured(i, jgen);
			}
			jgen.writeEndObject();
		}
		jgen.writeEndObject();
	}

	/**
	 * This will be called once per point in an origin/destination pointset, and
	 * once per origin in a one- or many-to-many indicator.
	 */
	protected void writeStructured(int i, JsonGenerator jgen) throws IOException {
		jgen.writeObjectFieldStart("structured");
		for (String cat_id : categories.keySet()) {
			jgen.writeObjectFieldStart(cat_id);
			for (String attr_id : categories.get(cat_id).attributes.keySet()) {

				Attribute attr = categories.get(cat_id).attributes.get(attr_id);

				if (attr.histogram != null) {
					jgen.writeObjectField(attr.id, attr.histogram[i]);
				} else if (attr.magnitudes != null) {
					if (attr.magnitudes[i] > 0) { // skip zeros for space and
													// boolean/enum attribs
						jgen.writeNumberField(attr.id, attr.magnitudes[i]);
					}
				}
			}
			jgen.writeEndObject();
		}
		jgen.writeEndObject();
	}

	public PointSet slice(int start, int end) {
		PointSet ret = new PointSet(end - start);

		ret.id = id;
		ret.label = label;
		ret.description = description;

		int n = 0;
		for (int i = start; i < end; i++) {
			ret.lats[n] = this.lats[i];
			ret.lons[n] = this.lons[i];
			ret.ids[n] = this.ids[i];
			ret.polygons[n] = this.polygons[i];
			n++;
		}

		for(Entry<String,Category> category : this.categories.entrySet()) {
			ret.categories.put( category.getKey(), category.getValue().slice(start,end) );
		}

		return ret;
	}

}
