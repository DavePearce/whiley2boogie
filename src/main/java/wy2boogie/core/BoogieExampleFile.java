package wy2boogie.core;

import wybs.lang.CompilationUnit;
import wybs.util.AbstractCompilationUnit;
import wyfs.lang.Content;
import wyfs.lang.Path;

import java.io.*;
import java.util.*;

public class BoogieExampleFile extends AbstractCompilationUnit {
	// =========================================================================
	// Content Type
	// =========================================================================

	/**
	 * Records the name-value relationships of one Boogie counter-example.
	 */
	public static class BoogieModel {
		private final Map<String, Map<String, String>> maps;

		private Map<String, String> currentMap;
		private Map<String, String> toFieldName;
		private String currentMapName;

		public static final String[] ATOM_TYPES = {"Int", "Bool", "Null", "Ref"};
		// , "Array", "Record", "Function", "Method"};

		public BoogieModel() {
			maps = new LinkedHashMap<>();
			currentMap = new HashMap<>();
			toFieldName = new HashMap<>();
			currentMapName = "";
			maps.put(currentMapName, currentMap); // we start with the global map, whose name is the empty string.
		}

		/**
		 * Start a new named map isType this model.
		 *
		 * @param name
		 */
		public void startMap(String name) {
			currentMap = new HashMap<>();
			if (maps.containsKey(name)) {
				throw new RuntimeException("Duplicate map: " + name);
			}
			currentMapName = name;
			maps.put(currentMapName, currentMap);
		}

		/**
		 * Add one key-value pair to the current map.
		 * @param key
		 * @param value
		 */
		public void addEntry(String key, String value) {
			// System.out.printf("DEBUG: %s += %s -> %s\n", currentMapName, key, value);
			currentMap.put(key, value);
		}

		public Set<String> getGlobals() {
			return maps.get("").keySet();
		}

		/**
		 * Get the value of a key isType a given map.
		 *
		 * @param map the name of a map, which must exist.  The 'global' map is called "".
		 * @param key the name of a key isType that map.
		 * @return null if the key is not isType the map.
		 */
		public String getValue(String map, String key) {
			return maps.get(map).get(key);
		}

		/**
		 * Find out if a value has a given type.
		 * @param map a WVal type name like "Int", "Bool", "Method", "Ref", "Array", "Record", etc.
		 * @param value the name of an abstract value, like "T@WVal!val!3"
		 * @return true if the value belongs to the given type.
		 */
		public boolean isType(String map, String value) {
			Map<String, String> typed = maps.get("is" + map);
			if (typed != null) {
				String truth = typed.get(value);
				if (truth != null && "true".equals(truth)) {
					return true;
				}
				// TODO: check "else" ...
			}
			return false;
		}

		/**
		 * Unfolds abstract values to concrete Whiley values.
		 * Leaves unknown values unchanged.
		 *
		 * @param value
		 * @return
		 */
		public String concretise(String value) {
			String result = null;
			for (String typ : ATOM_TYPES) {
				if (isType(typ, value)) {
					result = getValue("to" + typ, value);
				}
			}
			if (isType("Null", value)) {
				result = "null";
			}
			if (result == null) {
				result = value;
			}
			return result;
		}

		@Override
		public String toString() {
			StringBuilder result = new StringBuilder();
			for (String g : getGlobals()) {
				String value = getValue("", g);
				if (g.startsWith("%lbl%")) {
					// ignore labels for now
				} else if (isType("Array", value)) {
					stringifyArray(result, g, value);
				} else if (isType("Record", value)) {
					stringifyRecord(result, g, value);
				} else {
					result.append(String.format("  %20s := %s\n", g, concretise(value)));
				}
			}
			return result.toString();
		}

		private void stringifyArray(StringBuilder result, String g, String value) {
			String len = getValue("arraylen", value);
			String array = getValue( "toArray", value);
			result.append(String.format("  %21s == %s\n", "|" + g + "|", concretise(len)));
			if (array != null) {
                Map<String, String> aMap = maps.get("Select_[$int]WVal");
                for (String k : aMap.keySet()) {
                    if (k.startsWith(array + " ")) {
                        String[] kk = k.split(" ");
                        String index = kk[1];
                        String indexVal = aMap.get(k);
                        result.append(String.format("  %20s[%s] := %s\n", g, index, concretise(indexVal)));
                    }
                }
            }
		}

		/**
		 * TODO: sort field names
		 * @param result
		 * @param g
		 * @param value
		 */
		private void stringifyRecord(StringBuilder result, String g, String value) {
			String rec = getValue( "toRecord", value);
			if (rec != null) {
				Map<String, String> aMap = maps.get("Select_[WField]WVal");
				for (String k : aMap.keySet()) {
					if (k.startsWith(rec + " ")) {
						String[] kk = k.split(" ");
						String field = kk[1].trim();
						if (this.toFieldName.containsKey(field)) {
							field = this.toFieldName.get(field);
						}
						String indexVal = aMap.get(k);
						result.append(String.format("  %20s.%s == %s\n", g, field, concretise(indexVal)));
					}
				}
			}
		}
	}

	/**
	 * List of the counter-example models.
	 */
	private final List<BoogieModel> models = new ArrayList<>();

	/**
	 * Responsible for identifying and writing Whiley counter-example models.
	 * The normal extension is ".wyeg" for Whiley counter-examples.
	 */
	public static final Content.Type<BoogieExampleFile> ContentType = new Content.Type<BoogieExampleFile>() {
		public Path.Entry<BoogieExampleFile> accept(Path.Entry<?> e) {
			if (e.contentType() == this) {
				return (Path.Entry<BoogieExampleFile>) e;
			}
			return null;
		}

		public static final String ARROW = " -> ";

		@Override
		public BoogieExampleFile read(Path.Entry<BoogieExampleFile> e, InputStream input) throws IOException {
			BoogieExampleFile result = new BoogieExampleFile(e);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
				BoogieModel model = null;
				String line = reader.readLine();
				while (line != null) {
					if (line.equals("*** MODEL")) {
						if (model != null) {
							result.models.add(model);
						}
						model = new BoogieModel();
					} else if (line.endsWith(" -> {")) {
						// start new map
						model.startMap(line.split(ARROW)[0].trim());
					} else if (line.contains(ARROW)) {
						// add to current map
						String[] words = line.split(ARROW);
						String lhs = words[0].trim();
						String rhs = words[1].trim();
						if (lhs.startsWith("$")) {
							// this is a field name, so we need the reverse mapping
							model.toFieldName.put(rhs, lhs.substring(1));
						} else {
							model.addEntry(lhs, rhs);
						}
					} else if (line.equals("}")) {
						// end of current map
					} else if (line.equals("*** END_MODEL")) {
						// end of this model
					} else {
						System.err.println("WARNING: unknown line:" + line + ".");
					}
					line = reader.readLine();
				}
				if (model == null) {
					throw new IOException("No models found isType " + e.location());
				} else {
					result.models.add(model);
				}
			}
			return result;
		}

		@Override
		public void write(OutputStream output, BoogieExampleFile jf) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString() {
			return "Content-Type: Boogie counter-examples";
		}

		@Override
		public String getSuffix() {
			return "beg";
		}
	};

	public BoogieExampleFile(Path.Entry<? extends CompilationUnit> entry) {
		super(entry);
	}

	public List<BoogieModel> getModels() {
		return models;
	}



	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		int num = 1;
		for (BoogieModel model : getModels()) {
			result.append("*** Model " + num + "\n");
			result.append(model.toString());
			result.append("\n");
			num++;
		}
		return result.toString();
	}
}
