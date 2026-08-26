package pseudo.gen;


import network.DrmLoader;
import network.RailLoader;

import jp.ac.ut.csis.pflow.routing4.res.Network;
import utils.Progressbar;
import pseudo.acs.CensusODAccessor;
import pseudo.acs.DataAccessor;
import pseudo.acs.MNLParamAccessor;
import pseudo.acs.MkChainAccessor;
import pseudo.acs.PersonAccessor;
import pseudo.acs.SchoolRefAccessor;
import pseudo.res.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.TreeMap;

import java.util.logging.Logger;
import java.util.stream.Collectors;

import java.time.Duration;
import java.time.Instant;

public class NewScenario_a {
	protected Country japan;
	protected CensusODAccessor odAcs;
	protected String dir;
	protected Path dirnew;
	private int mfactor;
	private String sessionId;
	private String feedId;
	private static final String BUCKET_NAME = DataLoader_s3fs.BUCKET_NAME;
	private static final Logger logger = Logger.getLogger(NewScenario_a.class.getName());

	    public NewScenario_a(int mfactor, String sessionId, String feedId) {
        this.japan = new Country();
		// this.dir = Paths.get("./data");
		this.dir = "processing";
		this.dirnew = Paths.get("./download/" + sessionId);
		this.mfactor = mfactor;
		this.sessionId = sessionId;
		this.feedId = feedId;
    }

    public void createBaseData(String city){
		
		// load data
		Path cityFile = Paths.get("./data/city_boundary.csv");
		DataAccessor.loadCityData(cityFile.toString(), this.japan);

		System.out.println("Start loading the base data...\n\n");
		this.odAcs = new CensusODAccessor("./data/city_census_od.csv", this.japan);

		Path stationFile = Paths.get("./data/base_station.csv");
		Network station = DataAccessor.loadLocationData(stationFile.toString());
		this.japan.setStation(station);
		
		Path hospitalFile = Paths.get("./data/city_hospital.csv");
		DataAccessor.loadHospitalData(hospitalFile.toString(), this.japan);
				
		Path meshFile = Paths.get("./data/mesh_ecensus.csv");
		DataAccessor.loadEconomicCensus(meshFile.toString(), this.japan);
				
		// load data after ecensus
		Path tatemonoPath = Paths.get("./data/city_tatemono.csv");
		DataAccessor.loadZenrinTatemono(tatemonoPath.toString(), this.japan, 1);
    }

	public void commuterGen(){
		System.out.println("\nStart generating commuters' activities...");
        // load markov chains
		Map<EMarkov,Map<EGender,MkChainAccessor>> mrkMap = new HashMap<>();
		{
			String maleFile = Paths.get("./data/markov/tky2008_trip_01-10_labor_male_prob.csv").toString();
			String femaleFile = Paths.get("./data/markov/tky2008_trip_01-10_labor_female_prob.csv").toString();
			Map<EGender, MkChainAccessor> map = new HashMap<>();
			map.put(EGender.MALE, new MkChainAccessor(maleFile));
			map.put(EGender.FEMALE, new MkChainAccessor(femaleFile));
			mrkMap.put(EMarkov.LABOR, map);
		}		
		
		// load MNL parmaters
		Path mnlFile = Paths.get("./data/mnl/labor_params.csv");
		MNLParamAccessor mnlAcs = new MNLParamAccessor();
		mnlAcs.add(mnlFile.toString(), ELabor.WORKER);

		Path activityOutputDir =  this.dirnew.resolve("person/activity/");
		String householdDir = this.dirnew.resolve("person/agent").toString();
		// create an activity output directory if the directory doesn't exist.
        try {
            Files.createDirectories(activityOutputDir);
        } catch (IOException e) {
            e.printStackTrace();
        }

		// create activity
		Commuter Commuworker = new Commuter(this.japan, mrkMap, mnlAcs, this.odAcs);
		Progressbar pb = new Progressbar(new File(householdDir).list().length);
		int pbi = 1;
		for (File file : new File(householdDir).listFiles()) {
			if (file.getName().contains(".csv") && file.getName().contains("person_")) {
				List<HouseHold> households = PersonAccessor.load(file.getAbsolutePath(), new ELabor[]{ELabor.WORKER}, this.mfactor);
				// System.out.println(file.getName() + " " + households.size());
				Commuworker.assign(households);
				String resultName = activityOutputDir.resolve(String.format("%s_labor.csv", file.getName().replaceAll(".csv", ""))).toString();
				PersonAccessor.writeActivities(resultName, households);
			}
			pb.printProgress(pbi);
			pbi += 1;
		}
		System.out.println("\nEnd generating commuters' activities");
	}

    public void nonCommuterGen(){
		System.out.println("\nStart generating noncommuters' activity data...");
        // load markov data
		Map<EMarkov,Map<EGender,MkChainAccessor>> mrkMap = new HashMap<>();
		{	
			String maleFile = "./data/markov/tky2008_trip_14-15_nolabor_male_senior_prob.csv";
			String femaleFile = "./data/markov/tky2008_trip_14-15_nolabor_female_senior_prob.csv";			Map<EGender, MkChainAccessor> map = new HashMap<>();
			map.put(EGender.MALE, new MkChainAccessor(maleFile));
			map.put(EGender.FEMALE, new MkChainAccessor(femaleFile));
			mrkMap.put(EMarkov.NOLABOR_JUNIOR, map);
		}
		{	
			String maleFile = "./data/markov/tky2008_trip_14-15_nolabor_male_senior_prob.csv";
			String femaleFile = "./data/markov/tky2008_trip_14-15_nolabor_female_senior_prob.csv";
			Map<EGender, MkChainAccessor> map = new HashMap<>();
			map.put(EGender.MALE, new MkChainAccessor(maleFile));
			map.put(EGender.FEMALE, new MkChainAccessor(femaleFile));
			mrkMap.put(EMarkov.NOLABOR_SENIOR, map);
		}
		
		// load MNL parmaters
		String mnlFile = "./data/mnl/nolabor_params.csv";
		MNLParamAccessor mnlAcs = new MNLParamAccessor();
		mnlAcs.add(mnlFile, ELabor.NO_LABOR);
		
		
		// create activity
		NonCommuter worker = new NonCommuter(japan, mrkMap, mnlAcs);
		Path activityOutputDir =  this.dirnew.resolve("person/activity/");
		String householdDir = this.dirnew.resolve("person/agent").toString();
		
		Progressbar pb = new Progressbar(new File(householdDir).list().length);
		int pbi = 1;
		for (File file : new File(householdDir).listFiles()) {
			if (file.getName().contains(".csv")) {
				if (file.getName().contains("person_")) {
					List<HouseHold> households = PersonAccessor.load(file.getAbsolutePath(), new ELabor[]{ELabor.NO_LABOR}, this.mfactor);
					// System.out.println(file.getName() + " " + households.size());
					worker.assign(households);
					String resultName = activityOutputDir.resolve(String.format("%s_nolabor.csv", file.getName().replaceAll(".csv", ""))).toString();
					PersonAccessor.writeActivities(resultName, households);
				}
			}
			pb.printProgress(pbi);
			pbi += 1;
		}		
		System.out.println("\nEnd generating noncommuters' activity data");
	}

    public void studentGen(){
		System.out.println("\nStart generating students' activity data...");
        // load markov data
		Map<EMarkov,Map<EGender,MkChainAccessor>> mrkMap = new HashMap<>();
		{	
			String maleFile = "./data/markov/tky2008_trip_11-11_student1_prob.csv";
			Map<EGender, MkChainAccessor> map = new HashMap<>();
			map.put(EGender.MALE, new MkChainAccessor(maleFile));
			mrkMap.put(EMarkov.STUDENT1, map);
		}
		{	
			String maleFile = "./data/markov/tky2008_trip_12-13_student2_prob.csv";
			Map<EGender, MkChainAccessor> map = new HashMap<>();
			map.put(EGender.MALE, new MkChainAccessor(maleFile));
			mrkMap.put(EMarkov.STUDENT2, map);
		}		
		
		// load MNL parmaeters
		MNLParamAccessor mnlAcs = new MNLParamAccessor();
		String mnlFile1 = "./data/mnl/student1_params.csv";
		mnlAcs.add(mnlFile1, ELabor.PRE_SCHOOL);
		mnlAcs.add(mnlFile1, ELabor.PRIMARY_SCHOOL); 
		mnlAcs.add(mnlFile1, ELabor.SECONDARY_SCHOOL);
		
		String mnlFile2 = "./data/mnl/student2_params.csv";
		mnlAcs.add(mnlFile2, ELabor.HIGH_SCHOOL);
		mnlAcs.add(mnlFile2, ELabor.JUNIOR_COLLEGE);
		mnlAcs.add(mnlFile2, ELabor.COLLEGE);
								
		
		// prepare an accessor for school
		SchoolRefAccessor schAcs = new SchoolRefAccessor();
		Path activityOutputDir =  this.dirnew.resolve("person/activity/");
		String householdDir = this.dirnew.resolve("person/agent").toString();
			
		String prePref = "";
		
		// create trips
		Student worker = new Student(this.japan, mrkMap, mnlAcs, this.odAcs, schAcs);
	
		Progressbar pb = new Progressbar(new File(householdDir).list().length);
		int pbi = 1;
		for (File file : new File(householdDir).listFiles()) {
			if (file.getName().contains(".csv") && file.getName().contains("person")) {
					String name = file.getName();
					String pref = name.substring(7, 9);

					//　load school data
					if (!prePref.equals(pref)) {
						schAcs.loadS3(dir + String.format("/school/primary_%s.csv", pref), ELabor.PRIMARY_SCHOOL);
						schAcs.loadS3(dir + String.format("/school/secondary_%s.csv", pref), ELabor.SECONDARY_SCHOOL);
					}
					 System.out.println(pref);

					// load household
					List<HouseHold> households = PersonAccessor.load(file.getAbsolutePath(), new ELabor[] {
							ELabor.PRE_SCHOOL,
							ELabor.PRIMARY_SCHOOL,
							ELabor.SECONDARY_SCHOOL,
							ELabor.HIGH_SCHOOL,
							ELabor.JUNIOR_COLLEGE, ELabor.COLLEGE}, this.mfactor);
					// System.out.println(file.getName() + " household size: " + households.size());
					worker.assign(households);
					String resultName = activityOutputDir.resolve(String.format("%s_student.csv", file.getName().replaceAll(".csv",""))).toString();
					PersonAccessor.writeActivities(resultName, households);

					prePref = pref;
			}
			pb.printProgress(pbi);
			pbi += 1;
		}
		System.out.println("\nEnd generating students' activity data.");
		writeLog("\nEnd generating students activity data.", sessionId);

    }

    public void tripGen(String pref) throws Exception {

		System.out.println("\nStart creating trip data...");
//        Path modeFile = this.dir.resolve("act_transport.csv");
//		ModeAccessor modeAcs = new ModeAccessor(modeFile.toString());
		int prefCode = Integer.parseInt(pref); // 将字符串转换为整数
			Properties prop = loadProperties();
			Double carratio = Double.parseDouble(prop.getProperty("car." + prefCode));
			Double bikeratio = Double.parseDouble(prop.getProperty("bike." + prefCode));
		System.out.println(String.format("\n\nprefcode：%s", prefCode));
		System.out.println(String.format("\n\ncarratio：%s", carratio));
		System.out.println(String.format("\n\nbikeratio：%s", bikeratio));

		String roadFilePath = String.format("%s/network/drm_%02d.tsv", dir, Integer.parseInt(pref));
		List<String> roadFile = DataLoader_s3fs.readS3File(roadFilePath);
		Network road = DrmLoader.loadFromS3(roadFile);	
	
		String railFile = "./data/network/railnetwork.tsv";
		Network railway = RailLoader.load(railFile);

		// =======================================================
		// Generate trip with original gtfs data (before)
		// =======================================================	
		TripGenerator_WebAPI_batch_gtfsapi worker_before =
				new TripGenerator_WebAPI_batch_gtfsapi(this.japan, road, railway, this.sessionId, this.feedId + "_feed");

		Path tripInputDir = this.dirnew.resolve("person/activity/");
		Path tripOutputDir_before = this.dirnew.resolve("person/trip_before/");
		// create an output directory if the directory doesn't exist.
         try {
			Files.createDirectories(tripOutputDir_before);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// create trips (before)
        Progressbar pb_0 = new Progressbar(new File(tripInputDir.toString()).list().length);
		int j =0;
		for (File file : new File(tripInputDir.toString()).listFiles()) {
			if (file.getName().contains(".csv")) {
				String filename = tripInputDir.resolve(file.getName()).toString();
				List<Person> agents = PersonAccessor.loadActivity(filename, this.mfactor, carratio, bikeratio);
				// System.out.println(String.format("%s", file.getName()));
				worker_before.generateAll(agents);
//				worker.generate(agents);
				PersonAccessor.writeTrips(tripOutputDir_before.resolve(file.getName()).toString(), agents);
			}
			j += 1;
			if (j%1==0) {
				pb_0.printProgress(j);
			}
        }    
		System.out.println("\nEnd creating trip data (before).");
		writeLog("\nEnd creating trip data (before).", sessionId);

		// =======================================================
		// Generate trip with new gtfs data (after)
		// =======================================================	

		TripGenerator_WebAPI_batch_gtfsapi worker =
				new TripGenerator_WebAPI_batch_gtfsapi(this.japan, road, railway, this.sessionId, this.feedId);

		// Path tripInputDir = this.dirnew.resolve("person/activity/");
		Path tripOutputDir = this.dirnew.resolve("person/trip/");
         // create an output directory if the directory doesn't exist.
         try {
			Files.createDirectories(tripOutputDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// create trips
        Progressbar pb = new Progressbar(new File(tripInputDir.toString()).list().length);
		int i =0;
		for (File file : new File(tripInputDir.toString()).listFiles()) {
			if (file.getName().contains(".csv")) {
				String filename = tripInputDir.resolve(file.getName()).toString();
				List<Person> agents = PersonAccessor.loadActivity(filename, this.mfactor, carratio, bikeratio);
				// System.out.println(String.format("%s", file.getName()));
				worker.generateAll(agents);
//				worker.generate(agents);
				PersonAccessor.writeTrips(tripOutputDir.resolve(file.getName()).toString(), agents);
			}
			i += 1;
			if (i%1==0) {
				pb.printProgress(i);
			}
        }    
		System.out.println("\nEnd creating trip data. (after)");
		writeLog("\nEnd creating trip data. (after)", sessionId);

		// try {
			// System.out.println("Destroying session...");
//			worker.destroySession();
		// } catch (Exception e) {
			// System.err.println("Error destroying session: " + e.getMessage());
			// e.printStackTrace();
		// }
	}
    
	private static void writeLog(String content, String session_id){
		String directoryPath = "./download/" + session_id;
		File directory = new File(directoryPath);
		if (!directory.exists()) {
			directory.mkdirs();
		}

		String filename = directoryPath + "/java_sim.log";

		try(BufferedWriter bw = new BufferedWriter(new FileWriter(filename, true));){
			bw.write(content);
			// bw.newLine();
			} 
		catch (Exception e){
			e.printStackTrace();
		}
		
	}

	private static Properties loadProperties() throws IOException {
		InputStream inputStream = Commuter.class.getClassLoader().getResourceAsStream("config.properties");
		if (inputStream == null) {
			throw new FileNotFoundException("config.properties file not found in the classpath");
		}
		Properties prop = new Properties();
		prop.load(inputStream);
		return prop;
	}

    public static void main(String[] args) {
		String citycode = null;
		String sessionId = null;
		String feedId = null;

		if (args.length >= 3) {
            citycode = args[0];
            sessionId = args[1];
            feedId = args[2];
        }
        
		long startTime = System.currentTimeMillis();

		System.out.println("\n\n===========================================");
        System.out.println("\nPseudo Pflow Simulation Start...");
		writeLog("\nPseudo Pflow Simulation Start", sessionId);
		SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");  
  		Date date = new Date();  
    	writeLog(String.format("\n==============================\n\nStart generating data at %s\n", formatter.format(date)), sessionId);
		Instant start = Instant.now();

		try (Scanner scanner = new Scanner(System.in)) {
			if (citycode == null || citycode.isEmpty()) {
				System.out.print("\nEnter target city's 5-digit code: ");
				citycode = scanner.next();
			}
			String prefCode = citycode.substring(0, 2);
			int prefcode = Integer.parseInt(prefCode); // 将字符串转换为整数
			// System.out.println(String.format("\n\nprefcode：%s", prefcode));

			if (sessionId == null || sessionId.isEmpty()) {
				System.out.print("\nEnter session ID: ");
				sessionId = scanner.next();
			}

			if (feedId == null || feedId.isEmpty()) {
				System.out.print("\nEnter feed ID: ");
				feedId = scanner.next();
			}

			System.out.println("\nFinal Parameters:");
            System.out.println("City Code: " + citycode);
            System.out.println("Session ID: " + sessionId);
            System.out.println("Feed ID: " + feedId);

			Integer mfactor = 1;

			NewScenario_a worker = new NewScenario_a(mfactor, sessionId, feedId);
			// load base data
			System.out.println("\n===========================================");
			worker.createBaseData(citycode);
				writeLog(String.format("citycode:\t%s\nmfactor:\t%d\n", citycode, mfactor), sessionId);
			System.out.println("\n\nFinish loading the base data");
			System.out.println("===========================================\n\n");
			writeLog(String.format("\n==============================\n\nFinish loading the base data\n"), sessionId);


			System.out.println("\n\n==============Start creating Pseudo People Flow Data==============");
			Date date2 = new Date();  
		  	writeLog(String.format("\n==============================\n\nStart creating activity data at %s\n", formatter.format(date2)), sessionId);
			// Generate Commuters' activity
			worker.commuterGen();
  
			// // Generate NonCommuters' activity
			worker.nonCommuterGen();
 
			// // Generate Students' activity
			worker.studentGen();
			 
			// // Generate Trip data;
			Date date3 = new Date();  
			writeLog(String.format("\n==============================\n\nStart creating trip data at %s\n", formatter.format(date3)), sessionId);
			worker.tripGen(prefCode);
 
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			DataLoader_s3fs.closeS3Client();
		}

		Instant end = Instant.now();
		Duration timeElapsed = Duration.between(start, end);
		System.out.print("Execution time:" + timeElapsed.toMinutes() + " minutes");

		Date enddate = new Date();
		writeLog(String.format("\n\nDone generating data at %s\n==============================\n\n", formatter.format(enddate)), sessionId);
        System.out.print("Finish Creating Pseudo Pflow Simulation Data.\n\n");

		long endTime = System.currentTimeMillis();
		long duration = (endTime - startTime)/1000/60;
		writeLog(String.format("Caculation Time: \t%s minutes", duration), sessionId);
    }
}
