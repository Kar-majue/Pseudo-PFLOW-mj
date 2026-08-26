package pseudo.acs;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pseudo.gen.DataLoader_s3fs;
import pseudo.res.ELabor;
import pseudo.res.GLonLat;

public class SchoolRefAccessor {
	private Map<ELabor, Map<String, GLonLat>> data;
	
	public SchoolRefAccessor() {
		this.data = new HashMap<>();
	}
	
	public GLonLat getId(ELabor labor, String householdId) {
		if (data.containsKey(labor)) {
			Map<String, GLonLat> map = data.get(labor);
			if (map.containsKey(householdId)) {
				return map.get(householdId);
			}
		}
		return null;
	}
	
	public void load(String filename, ELabor labor) {
		// initialize
		Map<String, GLonLat> map = new HashMap<>();
		this.data.put(labor, map);
		// load ref data
		try (BufferedReader br = new BufferedReader(new FileReader(filename));){
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
            	String[] items = line.split(",");
            	String household = String.valueOf(items[0]);
            	String gcode = String.valueOf(items[1]);
	            double lon = Double.valueOf(items[2]);
	            double lat = Double.valueOf(items[3]);
	            map.put(household, new GLonLat(lon, lat, gcode));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
	}

    public void loadS3(String filename, ELabor labor) {
        // initialize
        Map<String, GLonLat> map = new HashMap<>();
        this.data.put(labor, map);
        // load ref data from S3
        try {
            List<String> lines = DataLoader_s3fs.readS3File(filename);
            if (lines.isEmpty()) {
                System.err.println("Error: No data found in " + filename);
                return;
            }
            
            lines.remove(0); // Skip header
            for (String line : lines) {
                String[] items = line.split(",");
                String household = items[0];
                String gcode = items[1];
                double lon = Double.parseDouble(items[2]);
                double lat = Double.parseDouble(items[3]);
                map.put(household, new GLonLat(lon, lat, gcode));
            }
        } catch (Exception e) {
            System.err.println("Error loading school reference data from S3: " + filename);
            e.printStackTrace();
        }
    }
}
