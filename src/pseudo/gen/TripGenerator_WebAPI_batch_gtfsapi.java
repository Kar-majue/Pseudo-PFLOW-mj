package pseudo.gen;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;

import jp.ac.ut.csis.pflow.geom2.DistanceUtils;
import jp.ac.ut.csis.pflow.geom2.ILonLat;
import jp.ac.ut.csis.pflow.geom2.LonLat;
import jp.ac.ut.csis.pflow.geom2.TrajectoryUtils;
import jp.ac.ut.csis.pflow.routing4.logic.Dijkstra;
import jp.ac.ut.csis.pflow.routing4.logic.linkcost.LinkCost;
import jp.ac.ut.csis.pflow.routing4.logic.transport.ITransport;
import jp.ac.ut.csis.pflow.routing4.logic.transport.Transport;
import jp.ac.ut.csis.pflow.routing4.res.Link;
import jp.ac.ut.csis.pflow.routing4.res.Network;
import jp.ac.ut.csis.pflow.routing4.res.Node;
import jp.ac.ut.csis.pflow.routing4.res.Route;
import network.DrmLoader;
import network.RailLoader;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.util.internal.ThreadLocalRandom;
import pseudo.acs.DataAccessor;
import pseudo.acs.PersonAccessor;
import pseudo.res.*;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.*;

public class TripGenerator_WebAPI_batch_gtfsapi {

	private int batchSize = 100;
	private final Map<String, JsonNode> resultMap = new ConcurrentHashMap<>();
    private final Network drm;
	private final Network railway;

	private final SSLContext sslContext;
	private final PoolingHttpClientConnectionManager connManager;
	private final CloseableHttpClient httpClient;
	private final String sessionId;
	private final String appSession;
	private final String feedId;
	private final Set<String> missingReqIds = ConcurrentHashMap.newKeySet();
	private final Map<String, Map<String, String>> allParams = new ConcurrentHashMap<>();


	private static final double MIN_TRANSIT_DISTANCE = 1000;
	// private static final double MAX_SEARCH_STATION_DISTANCE = 5000;
	private static final double FARE_PER_KILOMETER = 7; // Japanese yen, only for vehicle
	private static final double FARE_PER_HOUR = 1200; // Japanese yen, all modes, possible to extend to prefecture level
	private static final double FATIGUE_INDEX_WALK = 2;
	private static final double FATIGUE_INDEX_BICYCLE = 0.9;
	private static final double FARE_INIT = 150; // Japanese yen, only for vehicle
	private static final double CAR_AVAILABILITY = 0.7; // Parameter for explain people using car without ownership


	public TripGenerator_WebAPI_batch_gtfsapi(Country japan, Network drm, Network railway, String appSession, String feedId) throws Exception {
		super();
		ensurePropertiesLoaded();
        this.drm = drm;
		this.railway = railway;
		this.sslContext = createSSLContext();
		this.connManager = createConnManager();
		this.httpClient = createHttpClient();
		this.sessionId = createSession();
		this.appSession = appSession;
		this.feedId = feedId;
	}

	private SSLContext createSSLContext() throws Exception {
		return SSLContextBuilder.create()
				.loadTrustMaterial(new TrustSelfSignedStrategy())
				.build();
	}

	private PoolingHttpClientConnectionManager createConnManager() {
		SSLContext sslContext;
		try {
			sslContext = SSLContextBuilder.create()
					.loadTrustMaterial(new TrustSelfSignedStrategy())
					.build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize SSL context", e);
		}

		SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
				sslContext,
				new String[]{"TLSv1.2", "TLSv1.3"},
				null,
				NoopHostnameVerifier.INSTANCE);

		PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(
				RegistryBuilder.<ConnectionSocketFactory>create()
						.register("https", sslSocketFactory)
						.register("http", PlainConnectionSocketFactory.INSTANCE)
						.build());

		connManager.setMaxTotal(32); // Adjust based on your expected total number of concurrent connections
		connManager.setDefaultMaxPerRoute(100); // Adjust per route limits based on your API and use case

		return connManager;
	}
	private CloseableHttpClient createHttpClient() {

		return HttpClients.custom()
				.setSSLContext(this.sslContext)
				.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
				.setConnectionManager(this.connManager)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setCookieSpec(CookieSpecs.STANDARD)
						.build())
				.build();
	}

	private static HttpResponse executePostRequest(CloseableHttpClient httpClient, HttpPost postRequest) throws Exception {
		return httpClient.execute(postRequest);
	}

	public String createSession() throws Exception{

		HttpPost createSessionPost = new HttpPost(prop.getProperty("api.createSessionURL"));

		List<NameValuePair> sessionParams = new ArrayList<>();
		sessionParams.add(new BasicNameValuePair("UserID", prop.getProperty("api.userID")));
		sessionParams.add(new BasicNameValuePair("Password", prop.getProperty("api.password")));
		createSessionPost.setEntity(new UrlEncodedFormEntity(sessionParams));

		HttpResponse sessionResponse = executePostRequest(this.httpClient, createSessionPost);
		if (sessionResponse.getStatusLine().getStatusCode() == 200) {
			String sessionResponseBody = EntityUtils.toString(sessionResponse.getEntity());
			System.out.println("Session created successfully");
			System.out.println(sessionResponseBody);
			return sessionResponseBody.split(",")[1].trim().replace("\r", "").replace("\n", "");
		} else {
			System.out.println("Failed to create session: " + sessionResponse.getStatusLine().getStatusCode());
			return "";
		}
	}
	
	public String getAppSession() {
		return appSession;
	}

	public String getFeedId() {
		return feedId;
	}

	protected synchronized double getRandom() {
		return ThreadLocalRandom.current().nextDouble();
	}
	
	private class TripTask implements Callable<Integer> {
		private int id;
		private final List<Person> listAgents;
		private int error;
		private int total;
		LinkCost linkCost = new LinkCost();
		Dijkstra routing = new Dijkstra(linkCost);

		public TripTask(int id, List<Person> listAgents){
			this.id = id;
			this.listAgents = listAgents;
			this.total = error = 0;
		}	
		
		private EPurpose convertHomeMode(ELabor labor) {
			switch(labor) {
			case WORKER:
				return EPurpose.OFFICE;
			case JOBLESS:
			case NO_LABOR:
			case UNDEFINED:
			case INFANT:
				return EPurpose.FREE;
			case PRE_SCHOOL:
			case PRIMARY_SCHOOL:
			case SECONDARY_SCHOOL:
			case HIGH_SCHOOL:
			case COLLEGE:
			case JUNIOR_COLLEGE:
			default:
				return EPurpose.SCHOOL;
			}
		}

		private ETransport getTransport(int mode) { // mode defined by WebAPI
			switch (mode) {
				case 4:		return ETransport.WALK;
				case 3:	return ETransport.BUS;
                case 2:		return ETransport.TRAIN;
				case 0: return ETransport.NOT_DEFINED;
				default:		return ETransport.CAR;
			}
		}

		private ETransport determineTransportMode(Person person, double distance, Route route, Map<String, String> mixedparams, JsonNode[] mixedResultsHolder){
			ETransport nextMode;

			Map<ETransport, Double> choices = new LinkedHashMap<>();

			if(route!=null){
				double roadtime = route.getCost(); // seconds
				double roadfare = FARE_INIT + route.getLength() / 1000 * FARE_PER_KILOMETER; // length in meters, 150 as initial cost to avoid short distance car travel
				double roadcost = roadfare + roadtime / 3600 * FARE_PER_HOUR;
				if(person.hasCar() || getRandom() < CAR_AVAILABILITY){
					choices.put(ETransport.CAR, roadcost);
				}

				double walktime = route.getLength() / 1.38;
				double walkcost = walktime / 3600 * FARE_PER_HOUR * FATIGUE_INDEX_WALK;
				choices.put(ETransport.WALK, walkcost);

				if(person.hasBike()){
					double biketime = walktime / 2;
					double bikecost = biketime / 3600 * FARE_PER_HOUR * FATIGUE_INDEX_BICYCLE;
					choices.put(ETransport.BICYCLE, bikecost);
				}
			}

			if(distance>MIN_TRANSIT_DISTANCE){
				mixedResultsHolder[0] = getMixedRoute(httpClient, appSession, sessionId, mixedparams);
				boolean publicTransit = mixedResultsHolder[0].path("num_station").asInt() > 0 && mixedResultsHolder[0].path("fare").asInt() > 0;
				if (publicTransit) {
					double mixedfare = mixedResultsHolder[0].get("fare").asDouble();
					double mixedtime = mixedResultsHolder[0].get("total_time").asDouble(); // Travel time from WebAPI is in minute
					double mixedcost = mixedfare + mixedtime / 60 * FARE_PER_HOUR;
					choices.put(ETransport.MIX, mixedcost);
				}
			}

			nextMode = choices.entrySet()
					.stream()
					.min(Comparator.comparing(Map.Entry::getValue))
					.map(Map.Entry::getKey)
					.orElse(ETransport.NOT_DEFINED);

			return nextMode;
		}

		// Methods to refactor and modularize the code

		private void handleMixedTransport(ETransport nextMode, EPurpose purpose, JsonNode[] mixedResultsHolder, List<SPoint> subpoints, List<SPoint> points, Person person, Activity next, Route route, long startTime, long endTime, LonLat oll, LonLat dll) {
			long mixedTime = mixedResultsHolder[0].path("total_time").asLong() * 60;
			endTime += mixedTime;
			long travelTime = mixedTime;
			long depTime = next.getStartTime() - travelTime;

			List<Node> nodes = extractNodesFromMixedResults(mixedResultsHolder);
			boolean publicTransit = mixedResultsHolder[0].path("num_station").asInt() > 0;
			List<JsonNode> currentSubtrip = new ArrayList<>();
			int lastMode = determineInitialTransportMode(mixedResultsHolder);

			processMixedTransportFeatures(mixedResultsHolder, currentSubtrip, lastMode, publicTransit, depTime, person, purpose);
			addTimeStampedSubpoints(nodes, startTime, endTime, nextMode, purpose, subpoints);
			points.addAll(subpoints);
		}

		private List<Node> extractNodesFromMixedResults(JsonNode[] mixedResultsHolder) {
			List<Node> nodes = new ArrayList<>();
			Node firstStationNode = null; // 记录当前段的第一个站点 Node
			Node lastStationNode = null;  // 记录当前段的最后一个站点 Node

			JsonNode routeData = mixedResultsHolder[0].path("features");
			boolean previousHasStation = false; // 记录前一个站点是否有 stationName
			int previousTransportation = -1;

			for (JsonNode feature : routeData) {
				JsonNode coordinates = feature.path("geometry").path("coordinates");
				String stationName = feature.path("properties").path("station").asText();
				int currentTransportation = feature.path("properties").path("transportation").asInt();
				boolean currentHasStation = !"null".equals(stationName);

				if ((previousHasStation && !currentHasStation) ||
					(previousTransportation != -1 && previousTransportation != currentTransportation)) {

					if (firstStationNode != null && lastStationNode != null && !firstStationNode.equals(lastStationNode)){
						LinkCost linkCost = new LinkCost(Transport.RAILWAY);
						Dijkstra routing = new Dijkstra(linkCost);
						Route route = routing.getRoute(railway,	firstStationNode.getLon(), firstStationNode.getLat(), lastStationNode.getLon(), lastStationNode.getLat());

						if (route != null && route.numNodes() > 0){
							List<Node> rail_nodes = route.listNodes();
							nodes.addAll(rail_nodes);
						}
					}
					firstStationNode = null;
					lastStationNode = null;
				}

				if (!currentHasStation && coordinates.isArray()) {
					double lon = coordinates.get(0).asDouble();
					double lat = coordinates.get(1).asDouble();
					Node node = new Node(feature.path("properties").path("id").asText(), lon, lat);
					nodes.add(node); // 直接添加该节点
				}

				if (currentHasStation && coordinates.isArray()) {
					double lon = coordinates.get(0).asDouble();
					double lat = coordinates.get(1).asDouble();
					Node node = new Node(feature.path("properties").path("id").asText(), lon, lat);

					if (firstStationNode == null || previousTransportation != currentTransportation) {
						firstStationNode = node; // 记录当前段的第一个站点
					}
					lastStationNode = node; // 持续更新为最后一个站点
				}

				previousHasStation = currentHasStation; // 更新状态
				previousTransportation = currentTransportation;
			}

		//  处理最后一段站点数据
		//	if (firstStationNode != null) {
		//		processStationSegment(firstStationNode, lastStationNode);
		//	}
			if (nodes.size()==0){
				System.out.println("error is here");
				System.out.println("stop!");
			}
			return nodes;
		}

		private int determineInitialTransportMode(JsonNode[] mixedResultsHolder) {
			return mixedResultsHolder[0].path("features").get(0).path("properties").path("transportation").asInt();
		}

		private void processMixedTransportFeatures(JsonNode[] mixedResultsHolder, List<JsonNode> currentSubtrip, int lastMode, boolean publicTransit, long depTime, Person person, EPurpose purpose) {
			JsonNode routeData = mixedResultsHolder[0].path("features");
			JsonNode prevNode = null;
			long currentTime = depTime;
			for (JsonNode feature : routeData) {
				int currentMode = feature.path("properties").path("transportation").asInt();

				if (currentMode != lastMode) {
					if (currentSubtrip.size() > 1) {
						if(lastMode==1){
							long traveltime = 300;
							currentTime += traveltime;
						}else{
							currentTime +=  mixedResultsHolder[0].path("total_transport_time").asLong() * 60;
						}
						addTripForSubtrip(currentSubtrip, lastMode, publicTransit, currentTime, person, purpose);
					}
					currentSubtrip = new ArrayList<>();
					currentSubtrip.add(prevNode);
					lastMode = currentMode;
				}
				currentSubtrip.add(feature);
				prevNode = feature;
			}
		}

		private void addTripForSubtrip(List<JsonNode> currentSubtrip, int lastMode, boolean publicTransit, long depTime, Person person, EPurpose purpose) {
			JsonNode ollCoords = currentSubtrip.get(0).path("geometry").path("coordinates");
			JsonNode dllCoords = currentSubtrip.get(currentSubtrip.size() - 1).path("geometry").path("coordinates");
//			ETransport mode = getTransport(currentSubtrip.get(1).path("properties").path("transportation").asInt());

			// 2025-02-20 feature railway interpolation with 1.2 algorithm
			int firstMode = currentSubtrip.get(0).path("properties").path("transportation").asInt();
			boolean allSame = true, hasMode2 = false, hasMode3 = false;

			for (JsonNode node : currentSubtrip) {
				int transportation = node.path("properties").path("transportation").asInt();
				if (transportation != firstMode) allSame = false;
				if (transportation == 2) hasMode2 = true;
				if (transportation == 3) hasMode3 = true;
			}

			int finalMode = allSame ? firstMode : (hasMode2 ? 2 : (hasMode3 ? 3 : lastMode));
			ETransport mode = getTransport(finalMode);

			if (mode == ETransport.CAR && publicTransit) {
				mode = ETransport.WALK;
			}
			if (ollCoords.isArray() && dllCoords.isArray()) {
				LonLat moll = new LonLat(ollCoords.get(0).asDouble(), ollCoords.get(1).asDouble());
				LonLat mdll = new LonLat(dllCoords.get(0).asDouble(), dllCoords.get(1).asDouble());
				// depTime += (long) (DistanceUtils.distance(moll, mdll) / getTravelSpeed(mode.getId()));
				person.addTrip(new Trip(mode, purpose, depTime, moll, mdll));
			} else {
				System.out.println("No coordinate from API!");
			}
		}

		private void addTimeStampedSubpoints(List<Node> nodes, long startTime, long endTime, ETransport nextMode, EPurpose purpose, List<SPoint> subpoints) {
			Map<Node, Date> timeMap = TrajectoryUtils.putTimeStamp(nodes, new Date(startTime * 1000), new Date(endTime * 1000));
			addSubpoints(nodes, timeMap, nextMode, purpose, subpoints);
		}

		public String convertSecondsToHHMM(double totalSeconds) {
			// Calculate hours and minutes
			int hours = (int) (totalSeconds / 3600);
			int minutes = (int) ((totalSeconds % 3600) / 60);

			// Format hours and minutes to HHMM as WebAPI requests
			return String.format("%02d%02d", hours, minutes);
		}

		private List<Map<String, String>> getParamList(GLonLat oll, GLonLat dll, long startTime, String appSession, String feedid) {
			List<Map<String, String>> paramList = new ArrayList<>();
			Map<String, String> params = new HashMap<>();
		
			params.put("UnitTypeCode", "2");
			params.put("StartLongitude", String.valueOf(oll.getLon()));
			params.put("StartLatitude", String.valueOf(oll.getLat()));
			params.put("GoalLongitude", String.valueOf(dll.getLon()));
			params.put("GoalLatitude", String.valueOf(dll.getLat()));
		
			String gtfscalendarPath = String.format("./download/%s/%s/calendar.txt", appSession, feedid);
			String gtfscalendarDatesPath = String.format("./download/%s/%s/calendar_dates.txt", appSession, feedid);
			// String appDate = readStartDate(gtfscalendarPath);
			String appDate = findValidServiceDate(gtfscalendarPath, gtfscalendarDatesPath, appSession);
		
			if (appDate == null) {
				appDate = "20240401";
				writeLog("Warning: Unable to read start_date. Using default 20240401.\n", appSession);
			}
		
			params.put("AppDate", appDate);
			params.put("AppTime", convertSecondsToHHMM(startTime));
			params.put("StartGoalType", "1");
		
			paramList.add(params);
			return paramList;
		}

		private int process(Person person) {
			List<SPoint> points = new ArrayList<>();

			List<Activity> activities = person.getActivities();
			Activity pre = activities.get(0);

			try {
				if (activities.size() == 1) {
					person.addTrip(new Trip(ETransport.NOT_DEFINED, EPurpose.HOME, 0, pre.getLocation(), pre.getLocation()));

					Calendar cl = Calendar.getInstance();
					Date startDate = new Date(0);
					configureCalendar(cl, startDate);
					startDate = cl.getTime();
					points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), startDate, ETransport.NOT_DEFINED, EPurpose.HOME));
					Date endDate = new Date(86399000);
					configureCalendar(cl, endDate);
					endDate = cl.getTime();
					points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), endDate, ETransport.NOT_DEFINED, EPurpose.HOME));
					person.addTrajectory(points);
				} else {
					for (int i = 1; i < activities.size(); i++) {
						List<SPoint> subpoints = new ArrayList<>();

						Activity next = activities.get(i);
						GLonLat oll = pre.getLocation();
						GLonLat dll = next.getLocation();

						long startTime = next.getStartTime();
						long endTime = startTime;

						EPurpose purpose = next.getPurpose();
						double distance = DistanceUtils.distance(oll, dll);

						if (distance > 0) {
							ETransport nextMode;
							Map<String, String> mixedparams = buildMixedParams(oll, dll, startTime);

							JsonNode[] mixedResultsHolder = new JsonNode[1];

							Route route = routing.getRoute(drm, oll.getLon(), oll.getLat(), dll.getLon(), dll.getLat());
							nextMode = determineTransportMode(person, distance, route, mixedparams, mixedResultsHolder);

							int multiplier = calculateMultiplier(nextMode);
							long travelTime = 0;

							if (nextMode == ETransport.WALK || nextMode == ETransport.BICYCLE || nextMode == ETransport.CAR) {
								travelTime = calculateTravelTime(route, multiplier);
								endTime += travelTime;

								List<Node> nodes = route.listNodes();
								Map<Node, Date> timeMap = TrajectoryUtils.putTimeStamp(nodes, new Date(startTime * 1000), new Date(endTime * 1000));
								addSubpoints(nodes, timeMap, nextMode, purpose, subpoints);

								List<Link> links = route.listLinks();
								assignLinksToSubpoints(subpoints, links);
								points.addAll(subpoints);

								long depTime = next.getStartTime() - travelTime;
								person.addTrip(new Trip(nextMode, purpose, depTime, oll, dll));
							} else if (nextMode == ETransport.MIX) {
								if (mixedResultsHolder[0].path("features").get(0) == null) {
									System.out.println("empty mixed results!");
								}
								handleMixedTransport(nextMode, purpose, mixedResultsHolder, subpoints, points, person, next, route, startTime, endTime, oll, dll);
							} else {
								person.addTrip(new Trip(ETransport.NOT_DEFINED, next.getPurpose(), next.getStartTime(), pre.getLocation(), pre.getLocation()));
								Calendar cl = Calendar.getInstance();
								Date startDate = new Date(next.getStartTime());
								configureCalendar(cl, startDate);
								startDate = cl.getTime();
								points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), startDate, ETransport.NOT_DEFINED, EPurpose.HOME));
								Date endDate = new Date(next.getStartTime() + 300);
								configureCalendar(cl, endDate);
								endDate = cl.getTime();
								points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), endDate, ETransport.NOT_DEFINED, EPurpose.HOME));
								person.addTrajectory(points);
							}
						} else {
							person.addTrip(new Trip(ETransport.NOT_DEFINED, next.getPurpose(), next.getStartTime(), pre.getLocation(), pre.getLocation()));
							Calendar cl = Calendar.getInstance();
							Date startDate = new Date(next.getStartTime());
							configureCalendar(cl, startDate);
							startDate = cl.getTime();
							points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), startDate, ETransport.NOT_DEFINED, next.getPurpose()));
							person.addTrajectory(points);
						}

						pre = next;
					}
				}
				person.addTrajectory(points);
			} catch (Exception e){
				System.err.println("Exception at person: " + person);
				e.printStackTrace();

			}

			return 0;
		}

		private Map<String, String> buildMixedParams(GLonLat oll, GLonLat dll, long startTime) {
            Map<String, String> params = new HashMap<>();
            params.put("UnitTypeCode", "2");
            params.put("StartLongitude", String.valueOf(oll.getLon()));
            params.put("StartLatitude", String.valueOf(oll.getLat()));
            params.put("GoalLongitude", String.valueOf(dll.getLon()));
            params.put("GoalLatitude", String.valueOf(dll.getLat()));


            Map<String, String> mixedparams = new HashMap<>(params);
            mixedparams.put("TransportCode", "3"); // fixed as original
            mixedparams.put("AppDate", "20241001");
            mixedparams.put("AppTime", convertSecondsToHHMM(startTime));
            mixedparams.put("MaxRoutes", String.valueOf("9"));
            mixedparams.put("MaxRadius", String.valueOf("1000"));
            return mixedparams;
        }

		@Override
		public Integer call() throws Exception {
			try {
				for (int i=0; i < listAgents.size(); i+=batchSize) {
					int end = Math.min(i + batchSize, listAgents.size());
					List<Person>subBatch = listAgents.subList(i, end);
					batchProcess(subBatch);
				}
			}catch(Exception e) {
				e.printStackTrace();
			}
			// System.out.printf("[%d]-%d-%d%n",id, error, total);
			return 0;
		}
	}
	
	public void generateAll(List<Person> agents) {
		int numThreads = Runtime.getRuntime().availableProcessors();
		System.out.println("NumOfThreads: " + numThreads);
		writeLog(String.format("NumOfThreads: /%s\n", numThreads), appSession);

		List<Callable<Void>> tasks = new ArrayList<>();
		int totalAgents = agents.size();
		int stepSize = (int)Math.ceil(totalAgents * 1.0 / numThreads);

		for (int start = 0; start < totalAgents; start += stepSize) {
			int end = Math.min(start + stepSize, totalAgents);
			List<Person> subList = agents.subList(start, end);
			tasks.add(() -> {
				batchProcess(subList);
				return null;
			});
		}
		ExecutorService es = Executors.newFixedThreadPool(numThreads);
		try {
			es.invokeAll(tasks);
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			es.shutdown();
		}
	}

	private void batchProcess(List<Person> persons) {
		List<String> idsInBatch = new ArrayList<>();
		List<Map<String, String>>paramList = new ArrayList<>();

		for (Person p : persons) {
			List<Activity> acts = p.getActivities();
			for (int i=0; i<acts.size()-1; i++) {
				Activity pre = acts.get(i);
				Activity next = acts.get(i+1);

				double dist = DistanceUtils.distance(pre.getLocation(), next.getLocation());
				if (dist <= MIN_TRANSIT_DISTANCE) continue;

				GLonLat oll = pre.getLocation();
				GLonLat dll = next.getLocation();
				long startTime = next.getStartTime();

				String reqId = p.getId() + "_" + i;

				addParamItem(paramList, oll, dll, startTime, appSession, feedId, reqId);
				idsInBatch.add(reqId);

				if (paramList.size() >= batchSize){
					sendOneBatch(new ArrayList<>(paramList), new ArrayList<>(idsInBatch));
					paramList.clear();
					idsInBatch.clear();
				}
			}

		}
		if (!paramList.isEmpty()){
			sendOneBatch(new ArrayList<>(paramList), new ArrayList<>(idsInBatch));
			paramList.clear();
			idsInBatch.clear();
		}

		LinkCost linkCost = new LinkCost();
		Dijkstra routing = new Dijkstra(linkCost);

		for (Person p : persons) {
			List<SPoint> points = new ArrayList<>();

			List<Activity> acts = p.getActivities();
			if (acts.size() <=1 ) continue;

			for (int i=0; i<acts.size()-1; i++) {

				List<SPoint> subpoints = new ArrayList<>();

				Activity pre = acts.get(i);
				Activity nxt = acts.get(i+1);
				EPurpose purpose = nxt.getPurpose();
				GLonLat oll = pre.getLocation();
				GLonLat dll = nxt.getLocation();

				long startTime = nxt.getStartTime();
				long endTime = startTime;

				double dist = DistanceUtils.distance(oll, dll);
				// 3.1 CAR/WALK/BIKE
				Route route = routing.getRoute(drm, oll.getLon(), oll.getLat(),
						dll.getLon(), dll.getLat());
				double roadFare   = FARE_INIT + (route.getLength()/1000.0)* FARE_PER_KILOMETER;
				double roadTimeHr = route.getCost()/3600.0;
				double carCost    = roadFare + roadTimeHr*FARE_PER_HOUR;

				double walkTimeSec = route.getLength()/1.38;
				double walkCost    = (walkTimeSec/3600.0)*FARE_PER_HOUR*FATIGUE_INDEX_WALK;

				double bikeTimeSec = walkTimeSec/2.0;
				double bikeCost    = (bikeTimeSec/3600.0)*FARE_PER_HOUR*FATIGUE_INDEX_BICYCLE;

				// 3.2 GTFS
				String reqId = p.getId() + "_" + String.valueOf(i);

				JsonNode gNode = resultMap.get(reqId);

				double gtfsCost = Double.POSITIVE_INFINITY;
				String originStationName = null;
				long originStationTime = 0L;
				String destStationName = null;
				long destStationTime = 0L;

				if (gNode != null){
					double fare = gNode.path("fare").asDouble(0);
					if (fare==0) fare = 220;
					double totalTime = gNode.get("total_time").asDouble();
					double lenToStart = gNode.path("length_to_start_station").asDouble();
					if (totalTime > 0 && lenToStart < 1000) {
						gtfsCost = fare + (totalTime / 60.0) * FARE_PER_HOUR;
					}

					JsonNode features = gNode.path("features");
					if (features.isArray()) {
						for (JsonNode feature : features) {
							JsonNode stationNode = feature.path("properties").path("station");
							if (stationNode.isMissingNode() || stationNode.isNull()) {
								continue;
							}

							String stationName = stationNode.path("station_name").asText();
							String timeStr = stationNode.path("arrival_time").asText();

							if (stationName == null || stationName.isEmpty()) {
								continue;
							}

							if (timeStr == null || timeStr.trim().isEmpty()) {
								continue;
							}

							long t = HHMMtoSeconds(timeStr);

							// first station as origin
							if (originStationName == null) {
								originStationName = stationName;
								originStationTime = t;
							}
							// last station as destination
							destStationName = stationName;
							destStationTime = t;

							// System.out.println("Found station name: " + stationName + " at time: " + t + " = " + timeStr + " for reqId: " + reqId);
							// writeLog("Found station name: " + stationName + " at time: " + t + " = " + timeStr + " for reqId: " + reqId + "\n", appSession);

													// =====API Debugging Info======
						// System.out.println(
						// 	"Writing trip: originStation=" + originStationName + "(" + originStationTime + ")" + 
						// 	", destStation=" + destStationName + "(" + destStationTime + ")");
						// writeLog("Writing trip: originStation=" + originStationName + "(" + originStationTime + ")" + 
						// 	", destStation=" + destStationName + "(" + destStationTime + ")\n", appSession);
						
						// Map<String, String> param = allParams.get(reqId);
						// if (param != null) {
						// 	System.out.println("Parameters for reqId " + reqId + ":");
						// 	writeLog("Parameters for reqId " + reqId + ":\n", appSession);
						// 	System.out.println("Starttime for" + reqId + ":" + startTime);
						// 	writeLog("Starttime for" + reqId + ":" + startTime + "\n", appSession);
						// 	for (Map.Entry<String, String> entry : param.entrySet()) {
						// 		System.out.println("  " + entry.getKey() + ": " + entry.getValue());
						// 	}
						// } else {
						// 	System.err.println("No parameters stored in allParams for reqId: " + reqId);
						// }
						// 	writeLog("No parameters stored in allParams for reqId: " + reqId + "\n", appSession);
						}
					}
				}
//				String reqId = p.getId() + "_" + i + "_" + nxt.getStartTime();
//				BatchTripResponse gResp = gtfsResults.get(reqId);
//				if (gResp!=null) {
//					double fare      = gResp.getFare();
//					double totalTime = gResp.getTotalTime(); // 分钟
//					gtfsCost = fare + (totalTime/60.0)*FARE_PER_HOUR;
//				}

// 3.3 getMixedRoute
				double mixCost = Double.POSITIVE_INFINITY;
				JsonNode[] mixedResultsHolder = new JsonNode[1];
				if (dist>MIN_TRANSIT_DISTANCE) {

					Map<String, String> mixedparams = getStringStringMap(oll, dll, startTime, appSession, feedId);

					mixedResultsHolder[0] = getMixedRoute(httpClient, appSession, sessionId, mixedparams);
					boolean publicTransit = mixedResultsHolder[0].path("num_station").asInt() > 0 && mixedResultsHolder[0].path("fare").asInt() > 0;
					if (publicTransit) {
						double mixedfare = mixedResultsHolder[0].get("fare").asDouble();
						double mixedtime = mixedResultsHolder[0].get("total_time").asDouble(); // Travel time from WebAPI is in minute
						mixCost = mixedfare + mixedtime / 60 * FARE_PER_HOUR;
					}
				}

				// 3.4 Compare
				Map<ETransport,Double> costMap = new HashMap<>();
				costMap.put(ETransport.CAR, carCost);
				costMap.put(ETransport.WALK, walkCost);
				costMap.put(ETransport.BICYCLE, bikeCost);
				if (gtfsCost< Double.POSITIVE_INFINITY) costMap.put(ETransport.COMMUNITY, gtfsCost);
				if (mixCost< Double.POSITIVE_INFINITY) costMap.put(ETransport.MIX, mixCost);

				ETransport nextMode = costMap.entrySet().stream()
						.min(Comparator.comparingDouble(Map.Entry::getValue))
						.map(Map.Entry::getKey)
						.orElse(ETransport.NOT_DEFINED);

				int multiplier = calculateMultiplier(nextMode);
				long travelTime = 0;

				if (nextMode == ETransport.WALK || nextMode == ETransport.BICYCLE || nextMode == ETransport.CAR || nextMode == ETransport.COMMUNITY) {
					travelTime = calculateTravelTime(route, multiplier);
					endTime += travelTime;

					List<Node> nodes = route.listNodes();
					Map<Node, Date> timeMap = TrajectoryUtils.putTimeStamp(nodes, new Date(startTime * 1000), new Date(endTime * 1000));
					addSubpoints(nodes, timeMap, nextMode, purpose, subpoints);

					List<Link> links = route.listLinks();
					assignLinksToSubpoints(subpoints, links);
					points.addAll(subpoints);

					long depTime = nxt.getStartTime() - travelTime;

					Trip trip = new Trip(nextMode, purpose, depTime, oll, dll);

					if (nextMode == ETransport.COMMUNITY && originStationName != null) {
						trip.setOriginStation(originStationName);
						trip.setOriginStationTime(originStationTime);
						trip.setDestinationStation(destStationName);
						trip.setDestinationStationTime(destStationTime);
					}
					
					p.addTrip(trip);

				} else if (nextMode == ETransport.MIX) {
					if(mixedResultsHolder[0].path("features").get(0)==null){
						System.out.println("empty mixed results!");
						writeLog("empty mixed results!\n", appSession);
					}
					handleMixedTransport(nextMode, purpose, mixedResultsHolder, subpoints, points, p, nxt, route, startTime, endTime, oll, dll);
				} else {
					p.addTrip(new Trip(ETransport.NOT_DEFINED, nxt.getPurpose(), nxt.getStartTime(), pre.getLocation(), pre.getLocation()));
					Calendar cl = Calendar.getInstance();
					Date startDate = new Date(nxt.getStartTime());
					configureCalendar(cl, startDate);
					startDate = cl.getTime();
					points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), startDate, ETransport.NOT_DEFINED, EPurpose.HOME));
					Date endDate = new Date(nxt.getStartTime() + 300);
					configureCalendar(cl, endDate);
					endDate = cl.getTime();
					points.add(new SPoint(pre.getLocation().getLon(), pre.getLocation().getLat(), endDate, ETransport.NOT_DEFINED, EPurpose.HOME));
					p.addTrajectory(points);
				}

//                long travelSec = estimateTravelTime(best, route, gResp /*for GTFS*/, null /*mixJson*/);
//                long depTime   = nxt.getStartTime()- travelSec;
//                p.addTrip(new Trip(best, nxt.getPurpose(), depTime, oll, dll));
//
//                List<SPoint> subPoints = buildTrajectory(best, route, depTime, nxt.getStartTime(), gResp, oll, dll);
//                p.addTrajectory(subPoints);
			}
		}
	}

	private void sendOneBatch(List<Map<String,String>> paramList,
							  List<String>             idsInBatch) {
		// System.out.println(">>> sendOneBatch start, params=" + paramList.size() + ", ids=" + idsInBatch.size());

		// if (paramList.size() != idsInBatch.size()) {
		// 	System.err.println("MISMATCH: paramList size=" + paramList.size() 
		// 		+ ", idsInBatch size=" + idsInBatch.size());
		// 	for (int k = 0; k < idsInBatch.size(); k++) {
		// 		System.err.println("  id=" + idsInBatch.get(k));
		// 	}
		// }

		try {
			JsonNode raw = getGtfsBusRoute(httpClient,
					appSession, feedId, sessionId,
					paramList);

			List<JsonNode> results = new ArrayList<>();
			if (raw != null && raw.isArray()) raw.forEach(results::add);
			else if (raw != null) results.add(raw);

			int resultCount = results.size();
			int idCount = idsInBatch.size();

			// int n = Math.min(idCount, resultCount);
			for (int k = 0; k < idsInBatch.size(); k++) {
				String reqId = idsInBatch.get(k);
				if (k < paramList.size()) {
					Map<String, String> params = paramList.get(k);
					if (params != null) {
						allParams.put(reqId, new HashMap<>(params));
						// System.out.println("Saved params for reqId " + reqId + ": " + params);
					} else {
						System.err.println("Null params for reqId " + reqId);
					}
				} else {
					System.err.println("Missing param for reqId " + reqId + " (index out of range)");
				}
			}

			for (int i = 0; i < idCount; i++) {
				String reqId = idsInBatch.get(i);
				JsonNode result = (i < resultCount) ? results.get(i) : null;

				if (result != null && !result.isNull()) {
					resultMap.put(reqId, result);
					// System.out.println("Stored result for reqId: " + reqId);
				} else {
					missingReqIds.add(reqId);
					System.err.println("Null result for reqId: " + reqId);
				}
			}

		} catch (Exception e) {
			System.out.println("sendOneBatch function Error");
			e.printStackTrace();
		}
	}

	private void addSubpoints(List<Node> nodes, Map<Node, Date> timeMap, ETransport nextMode, EPurpose purpose, List<SPoint> subpoints) {
		for (ILonLat node : nodes) {
			Date date = timeMap.get(node);
			Calendar cl = Calendar.getInstance();
			configureCalendar(cl, date);
			date = cl.getTime();
			SPoint point = new SPoint(node.getLon(), node.getLat(), date, nextMode, purpose);
			subpoints.add(point);
		}
	}

	private void assignLinksToSubpoints(List<SPoint> subpoints, List<Link> links) {
		for (int k = 1; k <= links.size(); k++) {
			subpoints.get(k).setLink(links.get(k - 1).getLinkID());
		}
	}

	private int calculateMultiplier(ETransport mode) {
		switch (mode) {
			case WALK: return 6;
			case BICYCLE: return 3;
			case CAR: return 1;
			default: return 1;
		}
	}

	private void configureCalendar(Calendar calendar, Date date) {
		TimeZone timeZone = TimeZone.getTimeZone("Asia/Tokyo");
		calendar.setTime(date);
		calendar.setTimeZone(timeZone);
		calendar.add(Calendar.MILLISECOND, -timeZone.getOffset(calendar.getTimeInMillis()));
		calendar.add(Calendar.YEAR, 45);
		calendar.add(Calendar.MONTH, 9);
	}

	private long calculateTravelTime(Route route, int multiplier) {
		if (route != null) {
			return (long) route.getCost() * multiplier;
		} else {
			return 3600L;
		}
	}

	private int readAppDate() {

		return 20240401;
	}

	private static JsonNode parseConcatenatedJson(String raw, ObjectMapper mapper)
			throws IOException {

		JsonFactory factory = mapper.getFactory();
		JsonParser parser  = factory.createParser(raw);

		ArrayNode arr = mapper.createArrayNode();

		while (parser.nextToken() != null) {
			JsonNode node = mapper.readTree(parser);
			if (node != null) arr.add(node);
		}
		parser.close();

		if (arr.size() == 1) return arr.get(0);
		return arr;
	}

	public JsonNode getGtfsBusRoute(CloseableHttpClient httpClient, String appSession, String feedid, String sessionId, List<Map<String, String>> paramList) {
		long startTime = System.currentTimeMillis();
		HttpPost gtfsRoutePost = new HttpPost((prop.getProperty("api.getGTFSBusRouteURL")));

		// System.out.println("Sending GTFS request with params size: " + paramList.size());

		try {
			MultipartEntityBuilder builder = MultipartEntityBuilder.create();
			builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

			// convert list of param maps into a JSON array string
			ObjectMapper mapper = new ObjectMapper();
			String jsonParams = mapper.writeValueAsString(paramList);
			builder.addTextBody("params", jsonParams, ContentType.APPLICATION_JSON);
			// System.out.println("params: " + jsonParams);

			// add gtfs file (repeat this if multiple files)
			String gtfsFilePath = String.format("./download/%s/%s.zip", appSession, feedid);
			// System.out.println("Use Gtfs File: /%s\n " + gtfsFilePath);
			// String gtfsFilePath = "/mnt/free/kobe_parent.zip";
			File gtfsFile = new File(gtfsFilePath);
			if (!gtfsFile.exists()) {
				System.err.println("Error: GTFS file not found at " + gtfsFilePath);
				writeLog(String.format("Error: GTFS file not found at /%s\n", gtfsFilePath), appSession);
				return null;
			}
			builder.addPart("GTFS", new FileBody(gtfsFile));

			gtfsRoutePost.setEntity(builder.build());
			gtfsRoutePost.setHeader("Cookie", "WebApiSessionID=" + sessionId);

			HttpResponse gtfsRouteResponse = httpClient.execute(gtfsRoutePost);

			long endTime = System.currentTimeMillis();
			long duration = endTime - startTime;
			System.out.println("Request took: " + duration + " milliseconds");
			writeLog(String.format("Request took: /%s milliseconds.\n", duration), appSession);

			if (gtfsRouteResponse.getStatusLine().getStatusCode() == 200) {
				// System.out.println("GTFS Route API request successful.");
				String gtfsRouteResponseBody = EntityUtils.toString(gtfsRouteResponse.getEntity(), StandardCharsets.UTF_8);
				// System.out.println("API response raw: " + gtfsRouteResponseBody);
				// return mapper.readTree(gtfsRouteResponseBody);
				return parseConcatenatedJson(gtfsRouteResponseBody, mapper);
			} else {
				writeLog(String.format("Error: Failed to request GTFS Route. Status Code: /%s\n", gtfsRouteResponse.getStatusLine().getStatusCode()), appSession);
				System.err.println("Error: Failed to request GTFS Route. Status Code: " + gtfsRouteResponse.getStatusLine().getStatusCode());
				// System.out.println("GTFS API Request Parameters:");
				// for (Map.Entry<String, String> entry : paramList.entrySet()) {
				// System.out.println("➜ " + entry.getKey() + " = " + entry.getValue());
				// }
				// System.out.println(EntityUtils.toString(gtfsRouteResponse.getEntity(), StandardCharsets.UTF_8));
				return null;
			}
		} catch (Exception e) {
			System.err.println("Exception occurred while calling GTFS API: " + e.getMessage());
			writeLog(String.format("Exception occurred while calling GTFS API: /%s\n", e.getMessage()), appSession);
			e.printStackTrace();
			return null;
		}
	}

	private static JsonNode getMixedRoute(CloseableHttpClient httpClient, String appSession,String sessionid, Map<String, String> params) {
		HttpPost mixedRoutePost = new HttpPost(prop.getProperty("api.getMixedRouteURL"));

		List<NameValuePair> mixedRouteParams = new ArrayList<>();
		for (Map.Entry<String, String> entry : params.entrySet()) {
			mixedRouteParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
		}

        try {
            mixedRoutePost.setEntity(new UrlEncodedFormEntity(mixedRouteParams));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        mixedRoutePost.setHeader("Cookie", "WebApiSessionID=" + sessionid);

        HttpResponse mixedRouteResponse = null;
        try {
            mixedRouteResponse = executePostRequest(httpClient, mixedRoutePost);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ObjectMapper mapper = new ObjectMapper();

		if (mixedRouteResponse.getStatusLine().getStatusCode() == 200) {
            String mixedRouteResponseBody = null;
            try {
                mixedRouteResponseBody = EntityUtils.toString(mixedRouteResponse.getEntity());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                return mapper.readTree(mixedRouteResponseBody);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
			System.out.println("Failed to get mixed route: " + mixedRouteResponse.getStatusLine().getStatusCode());
            try {
                return mapper.readTree("");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
	}

	private static JsonNode getRoadRoute(CloseableHttpClient httpClient, String sessionid, Map<String, String> params) throws Exception {
		HttpPost roadRoutePost = new HttpPost(prop.getProperty("api.getRoadRouteURL"));

		List<NameValuePair> roadRouteParams = new ArrayList<>();
		for (Map.Entry<String, String> entry : params.entrySet()) {
			roadRouteParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
		}

		roadRoutePost.setEntity(new UrlEncodedFormEntity(roadRouteParams));
		roadRoutePost.setHeader("Cookie", "WebApiSessionID=" + sessionid);

		HttpResponse roadRouteResponse = executePostRequest(httpClient, roadRoutePost);
		ObjectMapper mapper = new ObjectMapper();

		if (roadRouteResponse.getStatusLine().getStatusCode() == 200) {
			String roadRouteResponseBody = EntityUtils.toString(roadRouteResponse.getEntity());
			return mapper.readTree(roadRouteResponseBody);
		} else {
			System.out.println("Failed to get road route: " + roadRouteResponse.getStatusLine().getStatusCode());
			return mapper.readTree("");
		}
	}

	private void handleMixedTransport(ETransport nextMode, EPurpose purpose, JsonNode[] mixedResultsHolder, List<SPoint> subpoints, List<SPoint> points, Person person, Activity next, Route route, long startTime, long endTime, LonLat oll, LonLat dll) {
		// 1. 混合輸送の総時間を取得して、endTimeに加算する
		long mixedTime = mixedResultsHolder[0].path("total_time").asLong() * 60;
		endTime += mixedTime;
		long travelTime = mixedTime;
		// 2. 出発時間を計算
		long depTime = next.getStartTime() - travelTime;

		// 3. ノードのリストを抽出
		List<Node> nodes = extractNodesFromMixedResults(mixedResultsHolder);
		// 4. 公共交通機関の利用を判定
		boolean publicTransit = mixedResultsHolder[0].path("num_station").asInt() > 0;
		// 5. 現在のサブトリップのリストを初期化
		List<JsonNode> currentSubtrip = new ArrayList<>();
		// 6. 初期の交通手段を決定
		int lastMode = determineInitialTransportMode(mixedResultsHolder);

		// 7. 混合輸送の各特徴を処理
		processMixedTransportFeatures(mixedResultsHolder, currentSubtrip, lastMode, publicTransit, depTime, person, purpose);
		// 8. タイムスタンプ付きサブポイントを追加
		addTimeStampedSubpoints(nodes, startTime, endTime, nextMode, purpose, subpoints);
		// 9. ポイントリストにサブポイントを追加
		points.addAll(subpoints);
	}

	private List<Node> extractNodesFromMixedResults(JsonNode[] mixedResultsHolder) {
		List<Node> nodes = new ArrayList<>();
		JsonNode routeData = mixedResultsHolder[0].path("features");
		for (JsonNode feature : routeData) {
			JsonNode coordinates = feature.path("geometry").path("coordinates");
			if (coordinates.isArray()) {
				double lon = coordinates.get(0).asDouble();
				double lat = coordinates.get(1).asDouble();
				nodes.add(new Node(feature.path("properties").path("id").toString(), lon, lat));
			} else {
				System.out.println("Coordinate from WebAPI is not an array!!");
				writeLog(String.format("Coordinate from WebAPI is not an array!!\n"), appSession);
			}
		}
		return nodes;
	}

	private int determineInitialTransportMode(JsonNode[] mixedResultsHolder) {
		return mixedResultsHolder[0].path("features").get(0).path("properties").path("transportation").asInt();
	}

	private void processMixedTransportFeatures(JsonNode[] mixedResultsHolder, List<JsonNode> currentSubtrip, int lastMode, boolean publicTransit, long depTime, Person person, EPurpose purpose) {
		JsonNode routeData = mixedResultsHolder[0].path("features");
		JsonNode prevNode = null;
		long currentTime = depTime;
		for (JsonNode feature : routeData) {
			int currentMode = feature.path("properties").path("transportation").asInt();

			if (currentMode != lastMode) {
				if (currentSubtrip.size() > 1) {
					if(lastMode==1){
						long traveltime = 300;
						currentTime += traveltime;
					}else{
						currentTime +=  mixedResultsHolder[0].path("total_transport_time").asLong() * 60;
					}
					addTripForSubtrip(currentSubtrip, publicTransit, currentTime, person, purpose);
				}
				currentSubtrip = new ArrayList<>();
				currentSubtrip.add(prevNode);
				lastMode = currentMode;
			}
			currentSubtrip.add(feature);
			prevNode = feature;
		}
	}

	private void addTimeStampedSubpoints(List<Node> nodes, long startTime, long endTime, ETransport nextMode, EPurpose purpose, List<SPoint> subpoints) {
		Map<Node, Date> timeMap = TrajectoryUtils.putTimeStamp(nodes, new Date(startTime * 1000), new Date(endTime * 1000));
		addSubpoints(nodes, timeMap, nextMode, purpose, subpoints);
	}

	private Map<String, String> getStringStringMap(GLonLat oll, GLonLat dll, long startTime, String appSession, String feedid) {
		Map<String, String> params = new HashMap<>();
		params.put("UnitTypeCode", "2");
		params.put("StartLongitude", String.valueOf(oll.getLon()));
		params.put("StartLatitude", String.valueOf(oll.getLat()));
		params.put("GoalLongitude", String.valueOf(dll.getLon()));
		params.put("GoalLatitude", String.valueOf(dll.getLat()));

		String gtfscalendarPath = String.format("./download/%s/%s/calendar.txt", appSession, feedid);
		String gtfscalendarDatesPath = String.format("./download/%s/%s/calendar_dates.txt", appSession, feedid);
		// String appDate = readStartDate(gtfscalendarPath);
		String appDate = findValidServiceDate(gtfscalendarPath, gtfscalendarDatesPath, appSession);

		if (appDate == null) {
			appDate = "20240401";
			writeLog("Warning: Unable to read start_date from calendar.txt. Using default date: 20240401.\n", appSession);
		}

		// System.out.printf("appDate: %s",appDate);

		Map<String, String> mixedparams = new HashMap<>(params);
		if(getRandom()>0.5){
			mixedparams.put("TransportCode", "1");
		}else {
			mixedparams.put("TransportCode", "3");
		}
		mixedparams.put("AppDate", appDate);
		mixedparams.put("AppTime", convertSecondsToHHMM(startTime));
		mixedparams.put("MaxRoutes", String.valueOf(1));
		mixedparams.put("MaxRadius", String.valueOf(1000));
		return mixedparams;
	}

	public String convertSecondsToHHMM(double totalSeconds) {
		// Calculate hours and minutes
		int hours = (int) (totalSeconds / 3600);
		int minutes = (int) ((totalSeconds % 3600) / 60);

		// Format hours and minutes to HHMM as WebAPI requests
		return String.format("%02d%02d", hours, minutes);
	}

	private long HHMMtoSeconds(String timeStr) {
		if (timeStr == null) return 0L;
		timeStr = timeStr.trim();
		if (timeStr.isEmpty()) return 0L;

		try {
			if (timeStr.contains(":")) {
				//  17:01 or 17:01:30
				String[] parts = timeStr.split(":");
				int h = Integer.parseInt(parts[0]);
				int m = (parts.length > 1) ? Integer.parseInt(parts[1]) : 0;
				int s = (parts.length > 2) ? Integer.parseInt(parts[2]) : 0;
				return h * 3600L + m * 60L + s;
			} else {
				//  1701 
				int v = Integer.parseInt(timeStr);
				int h = v / 100;
				int m = v % 100;
				return h * 3600L + m * 60L;
			}
		} catch (Exception e) {
			System.out.println("Fail to parse timeStr: " + timeStr);
			return 0L;
		}
	}

	private void addParamItem(List<Map<String, String>> paramList,
							  GLonLat oll,
							  GLonLat dll,
							  long    startTime,
							  String  appSession,
							  String  feedid,
							  String  reqId) {

		Map<String, String> params = new HashMap<>();

		params.put("UnitTypeCode", "2");
		params.put("StartLongitude", String.valueOf(oll.getLon()));
		params.put("StartLatitude",  String.valueOf(oll.getLat()));
		params.put("GoalLongitude",  String.valueOf(dll.getLon()));
		params.put("GoalLatitude",   String.valueOf(dll.getLat()));

		String gtfscalendarPath = String.format("./download/%s/%s/calendar.txt",
				appSession, feedid);
		String gtfscalendarDatesPath = String.format("./download/%s/%s/calendar_dates.txt", appSession, feedid);
		// String appDate = readStartDate(gtfscalendarPath);
		String appDate = findValidServiceDate(gtfscalendarPath, gtfscalendarDatesPath, appSession);

		// System.out.printf("appDate: %s",appDate);

		if (appDate == null) {
			appDate = "20240401";
			writeLog("Warning: Unable to read start_date, use default 20240401.\n", appSession);
		}

		params.put("AppDate",  appDate);
		params.put("AppTime",  convertSecondsToHHMM(startTime));
		params.put("StartGoalType", "1");
		params.put("RequestId", reqId);

		paramList.add(params);
	}

	private String findValidServiceDate(String calendarPath, String calendarDatesPath, String appSession) {
		try (BufferedReader calReader = new BufferedReader(new FileReader(calendarPath))) {
			String calLine;
			boolean isFirstLine = true;
			while ((calLine = calReader.readLine()) != null) {
				if (isFirstLine) {
					isFirstLine = false;
					continue;
				}
				String[] columns = calLine.split(",");
				if (columns.length < 10) continue;

				String serviceId = columns[0].trim();
				String startDateStr = columns[8].trim();
				String endDateStr = columns[9].trim();

				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
				LocalDate startDate = LocalDate.parse(startDateStr, formatter);
				LocalDate endDate = LocalDate.parse(endDateStr, formatter);

				// 运行日标志
				boolean[] weekdays = new boolean[]{
					columns[1].trim().equals("1"), // Monday
					columns[2].trim().equals("1"), // Tuesday
					columns[3].trim().equals("1"), // Wednesday
					columns[4].trim().equals("1"), // Thursday
					columns[5].trim().equals("1"), // Friday
					columns[6].trim().equals("1"), // Saturday
					columns[7].trim().equals("1")  // Sunday
				};

				// 读取 calendar_dates.txt
				Map<LocalDate, Integer> exceptionMap = new HashMap<>();
				try (BufferedReader dateReader = new BufferedReader(new FileReader(calendarDatesPath))) {
					String dateLine;
					boolean dateFirstLine = true;
					while ((dateLine = dateReader.readLine()) != null) {
						if (dateFirstLine) {
							dateFirstLine = false;
							continue;
						}
						String[] parts = dateLine.split(",");
						if (parts.length < 3) continue;
						if (!parts[0].trim().equals(serviceId)) continue;

						LocalDate date = LocalDate.parse(parts[1].trim(), formatter);
						int exceptionType = Integer.parseInt(parts[2].trim());
						exceptionMap.put(date, exceptionType);
					}
				}

				// find the first valid service date
				for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
					// calendar_dates takes precedence
					if (exceptionMap.containsKey(date)) {
						int type = exceptionMap.get(date);
						if (type == 1) {
							return date.format(formatter); // added explicitly
						} else if (type == 2) {
							continue; // cancelled explicitly
						}
					} else {
						// Otherwise, determine based on weekday
						int dayOfWeek = date.getDayOfWeek().getValue(); // 1 = Monday, 7 = Sunday
						boolean runsThatDay = weekdays[dayOfWeek - 1];
						if (runsThatDay) {
							return date.format(formatter);
						}
					}
				}
			}
		} catch (IOException e) {
			writeLog(String.format("Error reading calendar/calendar_dates: /%s\n" ,e.getMessage()), appSession);
		}
		return null;
	}

	// public static List<Stop> parseStops(String jsonData) throws IOException {
	// 	List<Stop> stops = new ArrayList<>();
	// 	ObjectMapper mapper = new ObjectMapper();
	// 	JsonNode root = mapper.readTree(jsonData);
	// 	JsonNode features = root.path("features");

	// 	for (JsonNode feature : features) {
	// 		JsonNode properties = feature.path("properties");
	// 		String stopName = properties.path("stop_name").asText();
	// 		JsonNode geometry = feature.path("geometry");
	// 		JsonNode coordinates = geometry.path("coordinates");
	// 		double lon = coordinates.get(0).asDouble();
	// 		double lat = coordinates.get(1).asDouble();
	// 		stops.add(new Stop(lat, lon, stopName));
	// 	}
	// 	return stops;
	// }
	
	private static Properties prop;
	private static synchronized void ensurePropertiesLoaded() throws Exception {
		if (prop == null) {
			loadProperties();
		}
	}

	private static void loadProperties() throws Exception {
		InputStream inputStream = Commuter.class.getClassLoader().getResourceAsStream("config.properties");
		if (inputStream == null) {
			throw new FileNotFoundException("config.properties file not found in the classpath");
		}
		prop = new Properties();
		prop.load(inputStream);
	}

	private static void writeLog(String content, String appSession){
		String directoryPath = "./download/" + appSession;
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

	public static void main(String[] args) throws Exception {

		String inputDir;
		String root;

		loadProperties();
		InputStream inputStream = Commuter.class.getClassLoader().getResourceAsStream("config.properties");
		if (inputStream == null) {
			throw new FileNotFoundException("config.properties file not found in the classpath");
		}
		Properties prop = new Properties();
		prop.load(inputStream);

		root = prop.getProperty("root");
		inputDir = prop.getProperty("inputDir");
		System.out.println("Root Directory: " + root);
		System.out.println("Input Directory: " + inputDir);
		
		int mfactor = 1;

		Country japan = new Country();

		// load data
		String railFile = String.format("%srailnetwork.tsv", inputDir+"/network/");
		Network railway = RailLoader.load(railFile);


		String cityFile = String.format("%scity_boundary.csv", inputDir);
		DataAccessor.loadCityData(cityFile, japan);

		String stationFile = String.format("%sbase_station.csv", inputDir);
		Network station = DataAccessor.loadLocationData(stationFile);
		japan.setStation(station);

		String outputDir = "/home/majue/sip_case_study/Pseudo-PFLOW/oyama_case/person/";

		ArrayList<Integer> prefectureCodes = new ArrayList<>(Arrays.asList(
				22
//				11
//				22, 16, 28,
//				13,14,12,11,
//				1,2,3,5,6,8,10,15,
//				17,20,21,23,24,25,
//				30,33,36,37,39,40,41,42,
//				44,46
		));

		String sessionid = "predefined-session-id"; // Replace with actual session ID
		String feedid = "predefined-feed-id"; // Replace with actual feed ID

		for (int i: prefectureCodes){

			File tripDir = new File(outputDir+"trip/", String.valueOf(i));
			File trajDir = new File(outputDir+"trajectory/", String.valueOf(i));
			System.out.println("Start prefecture:" + i +" "+ tripDir.mkdirs() +" "+ trajDir.mkdirs());
			String roadFile = String.format("%sdrm_%02d.tsv", inputDir+"/network/", i);

            Network road = DrmLoader.load(roadFile);
			Double carRatio = Double.parseDouble(prop.getProperty("car." + i));
			Double bikeRatio = Double.parseDouble(prop.getProperty("bike." + i));

			File actDir = new File(String.format("%sactivity/", outputDir), String.valueOf(i));
			System.out.println("Activity Directory: " + actDir);
			for(File file: Objects.requireNonNull(actDir.listFiles())){
				if (file.getName().contains(".csv")) {
					String tripFileName = outputDir + "trip/" + i + "/trip_" + file.getName().substring(7, 19) + ".csv";

					String trajectoryFileName = outputDir + "trajectory/" + i + "/trajectory_" + file.getName().substring(7,19) + ".csv";


					// Check if the files already exist
//					if (new File(tripFileName).exists() || new File(trajectoryFileName).exists()) {
//						continue; // Skip to the next iteration
//					}

					long starttime = System.currentTimeMillis();
					TripGenerator_WebAPI_batch_gtfsapi worker = new TripGenerator_WebAPI_batch_gtfsapi(japan, road, railway, sessionid, feedid);
					List<Person> agents = PersonAccessor.loadActivity(file.getAbsolutePath(), mfactor, carRatio, bikeRatio);
					System.out.printf("%s%n", file.getName());
					worker.generateAll(agents);
					PersonAccessor.writeTrips(tripFileName, agents);
					PersonAccessor.writeTrajectory(trajectoryFileName, agents);
					long endtime = System.currentTimeMillis();
					System.out.println(file.getName() + ": " + (endtime - starttime));
				}
			}

		}
		System.out.println("end");
	}	

	public class BatchTripRequest {
		private String requestId;
		private int UnitTypeCode;
		private double StartLongitude;
		private double StartLatitude;
		private double GoalLongitude;
		private double GoalLatitude;
		private int AppDate;
		private int AppTime;
		private int StartGoalType;

		public BatchTripRequest(String requestId,
								int unitTypeCode,
								double startLon, double startLat,
								double goalLon, double goalLat,
								int appDate, int appTime,
								int startGoalType) {
			this.requestId       = requestId;
			this.UnitTypeCode    = unitTypeCode;
			this.StartLongitude  = startLon;
			this.StartLatitude   = startLat;
			this.GoalLongitude   = goalLon;
			this.GoalLatitude    = goalLat;
			this.AppDate         = appDate;
			this.AppTime         = appTime;
			this.StartGoalType   = startGoalType;
		}

		// ----- Getter / Setter -----
		public String getRequestId() {
			return requestId;
		}
		public int getUnitTypeCode() {
			return UnitTypeCode;
		}
		public double getStartLongitude() {
			return StartLongitude;
		}
		public double getStartLatitude() {
			return StartLatitude;
		}
		public double getGoalLongitude() {
			return GoalLongitude;
		}
		public double getGoalLatitude() {
			return GoalLatitude;
		}
		public int getAppDate() {
			return AppDate;
		}
		public int getAppTime() {
			return AppTime;
		}
		public int getStartGoalType() {
			return StartGoalType;
		}
	}

	/** 批量API返回结果 */
	public static class BatchTripResponse {
		private String requestId;
		private double fare;      // 票价
		private double totalTime; // 总用时(分钟)
		private JsonNode raw;     // 可选, 保留原始 JSON

		public String getRequestId() {
			return requestId;
		}
		public double getFare() {
			return fare;
		}
		public double getTotalTime() {
			return totalTime;
		}
		public JsonNode getRaw() {
			return raw;
		}

		public void setRequestId(String requestId) {
			this.requestId = requestId;
		}
		public void setFare(double fare) {
			this.fare = fare;
		}
		public void setTotalTime(double totalTime) {
			this.totalTime = totalTime;
		}
		public void setRaw(JsonNode raw) {
			this.raw = raw;
		}
	}

	public class BatchRouteService {

		private static final String API_GET_GTFS_BUS_ROUTE_BATCH_URL =
				"https://pflow-api.csis.u-tokyo.ac.jp/webapi/GetGTFSBusRoute2";

		private final CloseableHttpClient httpClient;
		private final String sessionId;
		private final ObjectMapper mapper;

		public BatchRouteService(CloseableHttpClient httpClient, String sessionId) {
			this.httpClient = httpClient;
			this.sessionId  = sessionId;
			this.mapper     = new ObjectMapper();
		}

		public Map<String, BatchTripResponse> getGtfsBusRouteBatch(
				List<BatchTripRequest> requests,
				List<File> gtfsFiles) throws IOException {

			HttpPost post = new HttpPost(API_GET_GTFS_BUS_ROUTE_BATCH_URL);
			post.setHeader("Cookie", "WebApiSessionID=" + sessionId);

			String jsonParams = mapper.writeValueAsString(requests);

			MultipartEntityBuilder builder = MultipartEntityBuilder.create();

			builder.addTextBody("params", jsonParams, ContentType.APPLICATION_JSON);

			if (gtfsFiles != null) {
				for (File f : gtfsFiles) {
					builder.addPart("GTFS", new FileBody(f));
				}
			}

			post.setEntity(builder.build());

			HttpResponse resp = httpClient.execute(post);
			if (resp.getStatusLine().getStatusCode() != 200) {
				throw new IOException("Failed to call GTFS API, code="
						+ resp.getStatusLine().getStatusCode());
			}

			String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
			JsonNode root = mapper.readTree(body);
			if (!root.isArray()) {
				throw new IOException("Unexpected JSON: " + body);
			}

			List<BatchTripResponse> resultList = new ArrayList<>();
			for (JsonNode node : root) {
				BatchTripResponse r = new BatchTripResponse();
				r.setRequestId(node.path("requestId").asText(""));
				r.setFare(node.path("fare").asDouble(0.0));
				r.setTotalTime(node.path("total_time").asDouble(0.0));
				r.setRaw(node);

				resultList.add(r);
			}

			Map<String, BatchTripResponse> result = new HashMap<>();
			for (BatchTripResponse b : resultList) {
				result.put(b.getRequestId(), b);
			}
			return result;
		}


	}

	private ETransport getTransport(int mode) {
		switch (mode) {
			case 4:		return ETransport.WALK;
			case 3:	return ETransport.BUS;
			case 2:		return ETransport.TRAIN;
			case 0: return ETransport.NOT_DEFINED;
			default:		return ETransport.CAR;
		}
	}

	private void addTripForSubtrip(List<JsonNode> currentSubtrip, boolean publicTransit, long depTime, Person person, EPurpose purpose) {
		JsonNode ollCoords = currentSubtrip.get(0).path("geometry").path("coordinates");
		JsonNode dllCoords = currentSubtrip.get(currentSubtrip.size() - 1).path("geometry").path("coordinates");
		ETransport mode = getTransport(currentSubtrip.get(1).path("properties").path("transportation").asInt());
		if (mode == ETransport.CAR && publicTransit) {
			mode = ETransport.WALK;
		}
		if (ollCoords.isArray() && dllCoords.isArray()) {
			LonLat moll = new LonLat(ollCoords.get(0).asDouble(), ollCoords.get(1).asDouble());
			LonLat mdll = new LonLat(dllCoords.get(0).asDouble(), dllCoords.get(1).asDouble());
			person.addTrip(new Trip(mode, purpose, depTime, moll, mdll));
			depTime += (long) (DistanceUtils.distance(moll, mdll) / 8.33);
		} else {
			System.out.println("No coordinate from API!");
		}
	}

	private static class TripContext {
		Person person;
		Activity pre;
		Activity next;
		public TripContext(Person p, Activity pre, Activity next) {
			this.person = p;
			this.pre = pre;
			this.next = next;
		}
	}
}
