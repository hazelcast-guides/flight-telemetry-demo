package com.hazelcast.jet.demo;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetService;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.datamodel.KeyedWindowResult;
import com.hazelcast.jet.demo.Aircraft.VerticalDirection;
import com.hazelcast.jet.demo.types.WakeTurbulanceCategory;
import com.hazelcast.jet.demo.util.Util;
import com.hazelcast.jet.pipeline.*;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import org.python.core.PyFloat;
import org.python.core.PyInteger;
import org.python.core.PyList;
import org.python.core.PyString;
import org.python.core.PyTuple;
import org.python.modules.cPickle;

import java.io.BufferedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Consumer;

import static com.hazelcast.function.ComparatorEx.comparingInt;
import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.aggregate.AggregateOperations.allOf;
import static com.hazelcast.jet.aggregate.AggregateOperations.linearTrend;
import static com.hazelcast.jet.aggregate.AggregateOperations.maxBy;
import static com.hazelcast.jet.aggregate.AggregateOperations.summingDouble;
import static com.hazelcast.jet.aggregate.AggregateOperations.toList;
import static com.hazelcast.jet.demo.Aircraft.VerticalDirection.ASCENDING;
import static com.hazelcast.jet.demo.Aircraft.VerticalDirection.CRUISE;
import static com.hazelcast.jet.demo.Aircraft.VerticalDirection.DESCENDING;
import static com.hazelcast.jet.demo.Aircraft.VerticalDirection.UNKNOWN;
import static com.hazelcast.jet.demo.Constants.heavyWTCClimbingAltitudeToNoiseDb;
import static com.hazelcast.jet.demo.Constants.heavyWTCDescendAltitudeToNoiseDb;
import static com.hazelcast.jet.demo.Constants.mediumWTCClimbingAltitudeToNoiseDb;
import static com.hazelcast.jet.demo.Constants.mediumWTCDescendAltitudeToNoiseDb;
import static com.hazelcast.jet.demo.Constants.typeToLTOCycyleC02Emission;
import static com.hazelcast.jet.demo.types.WakeTurbulanceCategory.HEAVY;
import static com.hazelcast.jet.demo.util.Util.nearLHR;
import static com.hazelcast.jet.demo.util.Util.nearLCY;
import static com.hazelcast.jet.demo.util.Util.nearLGW;
import static com.hazelcast.jet.demo.util.Util.nearEWR;
import static com.hazelcast.jet.demo.util.Util.nearJFK;
import static com.hazelcast.jet.demo.util.Util.nearLGA;
import static com.hazelcast.jet.demo.util.Util.nearHND;
import static com.hazelcast.jet.demo.util.Util.nearNRT;
import static com.hazelcast.jet.demo.util.Util.setHazelcastInstance;
import static com.hazelcast.jet.pipeline.SinkBuilder.sinkBuilder;
import static java.util.Collections.emptySortedMap;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This demo reads an ADS-B telemetry stream from the [ADS-B Exchange](https://www.adsbexchange.com/)
 * (see [ADS-B Exchange API](https://www.adsbexchange.com/data)) which returns the
 * real-time positions of commercial aircraft flying within a defined radius of one or
 * more points around the globe.
 * <p>
 * The demo passes this flight telemetry data stream through a stream processing
 * pipeline created using the Hazelcast stream API. The pipeline consists of a series
 * of steps which filter, enrich and split the stream enabling some actions to be run
 * in parallel. The steps include multiple examples of how to use the stream API to
 * run custom code such as to perform geofencing during enrichment which adds the
 * local airport information to the data as well as examples of window functions
 * which are used to aggregate data over a sliding time window whilst running complex
 * custom algorithms which add co2 emissions and noise calculation (based on aircraft
 * type and vertical direction).
 * <p>
 * After all those calculations have been made the stream writes the results to
 * two maps (one for take-off and one for landing), the aircraft positions with co2
 * and noise calculations are sent to a Graphite sink for storage these are rendered
 * by a Grafana dashboard.
 * <p>
 * This demo can run in both online and offline mode. The online mode will retrieve
 * realtime flight positions from the ADS-B exchange API (please see the READM for 
 * instructions on how to sign-up for an ADS-B Exchange API key). The offline mode 
 * uses around 30 minutes of flight data captured during September 2022 which 
 * ADS-B Exchange have kindly allowed us to distribute for your convenience.
 * <p>
 * In summary, the demo will calculate following metrics in real-time
 * - Filter out planes outside defined airport / metropolitan areas
 * - Sliding over last 1 minute to detect, whether the plane is ascending, descending or staying in the same level 
 * - Based on the plane type and phase of the flight provides information about maximum noise levels nearby to the airport and estimated C02 emissions for a region
 * <p>
 * The result of these are sent to three sinks (take off map, landing map and Graphite)
 * finally the data in these sinks are rendered in Grafana.
 * <p>
 * The DAG used to model Flight Telemetry calculations can be seen below:
 * The following DAG (displayed as ascii) illustrates the pipeline and executions paths
 * the same DAG can be seen in the application log on startup or viewed as an SVG 
 * image in Hazelcast management center (the diagram in Management center is interactive 
 * allowing you to drill into and monitor each job stage)
 * <pre>
 * <pre>
 * <p>
 *
 *                                                  ┌──────────────────┐
 *                                                  │Flight Data Source│
 *                                                  └─────────┬────────┘
 *                                                            │
 *                                                            v
 *                                           ┌─────────────────────────────────┐
 *                                           │Filter Aircraft  in Low Altitudes│
 *                                           └────────────────┬────────────────┘
 *                                                            │
 *                                                            v
 *                                                  ┌───────────────────┐
 *                                                  │Assign Airport Info│
 *                                                  └─────────┬─────────┘
 *                                                            │
 *                                                            v
 *                                          ┌───────────────────────────────────┐
 *                                          │Calculate Linear Trend of Altitudes│
 *                                          └─────────────────┬─────────────────┘
 *                                                            │
 *                                                            v
 *                                               ┌─────────────────────────┐
 *                                               │Assign Vertical Direction│
 *                                               └────┬────┬──┬───┬───┬────┘
 *                                                    │    │  │   │   │
 *                        ┌───────────────────────────┘    │  │   │   └──────────────────────────┐
 *                        │                                │  │   └─────────┐                    │
 *                        │                                │  └─────────┐   │                    │
 *                        v                                v            │   │                    │
 *             ┌────────────────────┐          ┌──────────────────────┐ │   │                    │
 *             │Enrich with C02 Info│          │Enrich with Noise Info│ │   │                    │
 *             └──┬─────────────────┘          └───────────┬──────────┘ │   │                    │
 *                │                                        │            │   │                    │
 *                │                          ┌─────────────┘            │   │                    │
 *                │                          │          ┌───────────────┘   │                    │
 *                v                          v          │                   v                    v
 * ┌───────────────────────┐ ┌─────────────────────────┐│ ┌───────────────────────────┐ ┌──────────────────────────┐
 * │Calculate Avg C02 Level│ │Calculate Max Noise Level││ │Filter Descending Aircraft │ │Filter Ascending Aircraft │
 * └──────────────┬────────┘ └────────────┬────────────┘│ └─────────────┬─────────────┘ └─────────┬────────────────┘
 *                │                       │             │               │                         │
 *                │  ┌────────────────────┘             │               │                         │
 *                │  │  ┌───────────────────────────────┘               │                         │
 *                │  │  │                                               │                         │
 *                │  │  │                                               │                         │
 *                v  v  v                                               v                         v
 *           ┌─────────────┐                               ┌──────────────────────┐     ┌────────────────────────┐
 *           │Graphite Sink│                               │IMap Sink (landingMap)│     │IMap Sink (takingOffMap)│
 *           └─────────────┘                               └──────────────────────┘     └────────────────────────┘
 * </pre>
 */
public class FlightTelemetry {

    private static final String CO2_EMISSION_KEY_SUFFIX = "_C02_EMISSION";
    private static final String AVG_NOISE_KEY_SUFFIX = "_AVG_NOISE";

    private static final String SOURCE_URL = "https://adsbexchange-com1.p.rapidapi.com/v2/lat/%.6f/lon/%.6f/dist/%d/";
    private static final int SINK_PORT = 2004;
    private static final String TAKE_OFF_MAP = "takeOffMap";
    private static final String LANDING_MAP = "landingMap";
    private static final String FLIGHT_TELEMETRY_SINK_HOST;
    private static final String FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_KEY;
    private static final String FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_HOST;
    private static final String FLIGHT_TELEMETRY_USE_OFFLINE_DATA;
    private static final String FLIGHT_TELEMETRY_WRITE_TO_FILE;

    private static final Map<String, Map<String, String>> configData =
            Map.of(
                    "London", Map.of(
                            // Setting to radar radius to 70 KM to pickup approach traffic for the three main London airports
                            // (distance between airports is
                            //      39K between LHR and LGW,
                            //      42KM between LGW and LCY and
                            //      35KM between LCY and LHR)
                            "RADAR_RADIUS", "31", // Half max distance + 10KM

                            // This is the lat long for North London Cricket Club which is the center point of all London airports
                            "RADAR_LAT", "51.5798373",
                            "RADAR_LON", "-0.1387518",

                            // Floor is the minimum altitude we consider a flight for altitude trend analysis
                            // we want to detect aircraft off the ground but still ascending (usually 100
                            // + the altitude of the highest airport - Gatwick is 62M/203ft)
                            // Ceiling is the maximum altitude we consider a flight for altitude trend analysis (usually 3000 + the floor)
                            "FLOOR", "303",
                            "CEILING", "3303"
                    ),

                    "New York", Map.of(
                            // Setting to radar radius to 70 KM to pickup approach traffic for the three main New York airports
                            // (distance between airports is
                            //      33K between EWR and JFK,
                            //      16KM between JFK and LGA and
                            //      27KM between LGA and EWR)
                            "RADAR_RADIUS", "32", // Half max distance + 10KM

                            // This is the lat long for 16 Manhattan Ave, Brooklyn, NY 11206 an undistinguished address in Manhattan
                            // which is the approximate center point of JFK, EWR and LGA airports
                            "RADAR_LAT", "40.702654",
                            "RADAR_LON", "-73.944399",

                            // Floor is the minimum altitude we consider a flight for altitude trend analysis
                            // we want to detect aircraft off the ground but still ascending (usually 100
                            // + the altitude of the highest airport - LaGuardia is 6M/20ft)
                            // Ceiling is the maximum altitude we consider a flight for altitude trend analysis (usually 3000 + the floor)
                            "FLOOR", "120",
                            "CEILING", "3120"
                    ),
                    "Tokyo", Map.of(
                            // Setting to radar radius to 70 KM to pickup traffic for all Tokyo airports
                            // (distance between airports is
                            //      65K between NRT and HND)
                            "RADAR_RADIUS", "80",  // Half max distance + 10KM

                            // This is the lat long for Kaihimmakuhari Station
                            // which is the approximate center point of HND and NRT airports
                            "RADAR_LAT", "35.6484242",
                            "RADAR_LON", "140.0394978",

                            // Floor is the minimum altitude we consider a flight for altitude trend analysis
                            // we want to detect aircraft off the ground but still ascending (usually 100
                            // + the altitude of the highest airport - Narita is 43M/141ft)
                            // Ceiling is the maximum altitude we consider a flight for altitude trend analysis (usually 3000 + the floor)
                            "FLOOR", "241",
                            "CEILING", "3241"
                    )
            );

    static {
        System.setProperty("hazelcast.logging.type", "log4j");
        FLIGHT_TELEMETRY_SINK_HOST = getConfigurationParameter("FLIGHT_TELEMETRY_SINK_HOST", "127.0.0.1");
        FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_KEY = getConfigurationParameter("FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_KEY");
        FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_HOST = getConfigurationParameter("FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_HOST");
        FLIGHT_TELEMETRY_USE_OFFLINE_DATA = getConfigurationParameter("FLIGHT_TELEMETRY_USE_OFFLINE_DATA");
        FLIGHT_TELEMETRY_WRITE_TO_FILE = getConfigurationParameter("FLIGHT_TELEMETRY_WRITE_TO_FILE");
    }

    public static void main(String[] args) {
        boolean useOfflineDataSource = Boolean.parseBoolean(FLIGHT_TELEMETRY_USE_OFFLINE_DATA);
        boolean writeTelemetryToFile = Boolean.parseBoolean(FLIGHT_TELEMETRY_WRITE_TO_FILE);

        // If we are not using offline data check the required values have been passed
        if (!useOfflineDataSource) {
            if (
                    FlightDataSource.API_AUTHENTICATION_KEY.equals("YOUR_API_KEY_HERE")
            ) {
                if (FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_KEY.isEmpty()) {
                    System.err.println("""
                            ADSB_EXCHANGE_API_KEY must be set in one of the following :\s
                            \tenvironment variable ADSB_EXCHANGE_API_KEY\s
                            \tsystem property ADSB_EXCHANGE_API_KEY\s
                            \tor provide it in FlightDataSource.java\s
                            \s
                            Using historical flight data to simulate real-time stream!""");

                    useOfflineDataSource = true;
                } else {
                    FlightDataSource.API_AUTHENTICATION_KEY = FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_KEY;
                    System.out.println("Using ADSB Exchange API Key : " + FlightDataSource.API_AUTHENTICATION_KEY);
                }
            }

            if (
                    FlightDataSource.API_HOST.equals("YOUR_API_HOST_HERE")
            ) {
                if (FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_HOST.isEmpty()) {
                    System.err.println("""
                            ADSB_EXCHANGE_API_HOST must be set in one of the following :\s
                            \tenvironment variable ADSB_EXCHANGE_API_HOST\s
                            \tsystem property ADSB_EXCHANGE_API_HOST\s
                            \tor provide it in FlightDataSource.java\s
                            \s
                            Using historical flight data to simulate real-time stream!""");

                    useOfflineDataSource = true;
                } else {
                    FlightDataSource.API_HOST = FLIGHT_TELEMETRY_ADSB_EXCHANGE_API_HOST;
                    System.out.println("Using ADSB Exchange API Host : " + FlightDataSource.API_HOST);
                }
            }
        }

        if(writeTelemetryToFile && !useOfflineDataSource) {
            System.out.println("Flight telemetry for will be written to a file for future playback.");
        } else {
            System.out.println("WARNING: Both use offline data and write telemetry data to disk are turned on however this is not "
                    + "allowed, writing telemetry to disk will be turned off.");
            // Do not write offline data over itself
            writeTelemetryToFile = false;
        }

        HazelcastInstance hzInstance = getHzInstance();
        setHazelcastInstance(hzInstance);

        addListener(hzInstance.getMap(TAKE_OFF_MAP), a -> System.out.println("New aircraft taking off: " + a));
        addListener(hzInstance.getMap(LANDING_MAP), a -> System.out.println("New aircraft landing " + a));

        try {
            boolean finalUseOfflineDataSource = useOfflineDataSource;
            boolean finalWriteTelemetryToFile = writeTelemetryToFile;

            configData.keySet().parallelStream()
                    .peek(e -> {
                        StreamSource<Aircraft> dataSource;

                        double radarLatitude = Double.parseDouble(configData.get(e).get("RADAR_LAT"));
                        double radarLongitude = Double.parseDouble(configData.get(e).get("RADAR_LON"));
                        int radarRadius = Integer.parseInt(configData.get(e).get("RADAR_RADIUS"));
                        int floor = Integer.parseInt(configData.get(e).get("FLOOR"));
                        int ceiling = Integer.parseInt(configData.get(e).get("CEILING"));

                        // Build the data source class based on on-line / off-line mode
                        if (finalUseOfflineDataSource) {
                            dataSource = OfflineDataSource.getDataSource("", hzInstance, e, 10000);

                        } else {
                            dataSource = FlightDataSource.getDataSource(String.format(SOURCE_URL, radarLatitude, radarLongitude, radarRadius),
                                    10000
                            );
                        }
                        System.out.println("Building job for [" + e + "]\n" +
                                "\tradar latitude : " + radarLatitude +
                                "\tradar longitude : " + radarLongitude +
                                "\tradar radius : " + radarRadius +
                                "\tfloor : " + floor +
                                "\tceiling : " + ceiling);

                        Pipeline pipeline = buildPipeline(e, dataSource, finalWriteTelemetryToFile, floor, ceiling);

                        JetService jetService = hzInstance.getJet();
                        Job job = jetService.newJob(pipeline, new JobConfig().setName("FlightTelemetry-" + e)
                                .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE));
                        job.join();


                    }).findAny();
        } finally {
            Hazelcast.shutdownAll();
        }
    }

    private static String getConfigurationParameter(String name, String defaultValue) {


        Optional<String> systemPropertyValue = getSystemProperty(name);
        Optional<String> environmentVariableValue = getEnvironmentVariable(name);

        return systemPropertyValue.orElseGet(() -> environmentVariableValue.orElse(defaultValue));
    }

    private static String getConfigurationParameter(String name) {
        return getConfigurationParameter(name, "");
    }

    private static Optional<String> getSystemProperty(String name) {
        return Optional.ofNullable(System.getProperty(name));
    }

    private static Optional<String> getEnvironmentVariable(String name) {
        return Optional.ofNullable(System.getenv(name));
    }

    private static HazelcastInstance getHzInstance() {
        String bootstrap = System.getProperty("bootstrap");
        if (bootstrap != null && bootstrap.equals("true")) {
            return Hazelcast.bootstrappedInstance();
        }

        return Hazelcast.newHazelcastInstance();
    }

    /**
     * Builds and returns the Pipeline which represents the actual computation.
     */
    private static Pipeline buildPipeline(String cityName, StreamSource<Aircraft> dataSource, boolean writeTelemetryToFile, int FLOOR, int CEILING) {
        Pipeline p = Pipeline.create();

        SlidingWindowDefinition slidingWindow = WindowDefinition.sliding(60_000, 30_000);

        StreamStage<Aircraft> ac;

        ac = p
                .readFrom(dataSource)
                .withNativeTimestamps(SECONDS.toMillis(15));

        if (writeTelemetryToFile) {
            ac.writeTo(Sinks.json(Util.getFlightDataLogPath(cityName)));
        }

        // Filter aircraft whose altitude is less than 3000ft (plus floor), calculate linear trend of their altitudes
        // and assign vertical directions to them.
        StreamStage<KeyedWindowResult<String, Aircraft>> flights = ac.filter(a -> !a.isgnd() && a.getalt() > FLOOR &&
                        a.getalt() < CEILING).setName("Filter aircraft in low altitudes")
                .map(FlightTelemetry::assignAirport).setName("Assign airport")
                .window(slidingWindow)
                .groupingKey(Aircraft::getid)
                .aggregate(
                        allOf(toList(), linearTrend(Aircraft::getpos_time, Aircraft::getalt),
                                (events, coefficient) -> {
                                    Aircraft aircraft = events.get(events.size() - 1);
                                    aircraft.setverticaldirection(getVerticalDirection(coefficient));

                                    return aircraft;
                                })
                ).setName("Calculate linear trend of aircraft"); // (timestamp, aircraft_id, aircraft_with_assigned_trend)

        // Filter ascending flights
        StreamStage<KeyedWindowResult<String, Aircraft>> takingOffFlights = flights
                .filter(e -> e.getValue().getverticaldirection() == ASCENDING)
                .setName("Filter ascending aircraft");
        // Write ascending flights to an IMap
        takingOffFlights.writeTo(Sinks.map(TAKE_OFF_MAP)); // (aircraft_id, aircraft)

        //Filter descending flights
        StreamStage<KeyedWindowResult<String, Aircraft>> landingFlights = flights
                .filter(e -> e.getValue().getverticaldirection() == DESCENDING)
                .setName("Filter descending aircraft");
        // Write descending flights to an IMap
        landingFlights.writeTo(Sinks.map(LANDING_MAP)); // (aircraft_id, aircraft)

        // Enrich aircraft with the noise info and calculate max noise
        // in 60secs windows sliding by 30secs.
        StreamStage<KeyedWindowResult<String, Integer>> maxNoise = flights
                .map(e -> entry(e.getValue(), getNoise(e.getValue())))
                .setName("Enrich with noise info")// (aircraft, noise)
                .window(slidingWindow)
                .groupingKey(e -> e.getKey().getairport() + AVG_NOISE_KEY_SUFFIX)
                .aggregate(maxBy(comparingInt(Entry<Aircraft, Integer>::getValue)).andThen(Entry::getValue))
                .setName("Calculate max noise level");
        // (airport, max_noise)

        /* 
           For convenience, we just get the approximate loudest single aircraft in the window.

           If we wanted to calculate peak noise we could do something like calculate the combined noise:

                Sound pressure levels are expressed in decibels, which is a logarithmic scale. 
                Therefore, we cannot simply arithmetically add noise levels. For example, 35 dB
                plus 35 dB does not equal 70 dB. Part 5: Appendixes 5.3 To add two or more noise 
                levels, if the difference between the highest and next highest noise level is: 
                
                0–1 dB then add 3 dB to the higher level to give the total noise level 
                2–3 dB then add 2 dB to the higher level to give the total noise level 
                4–9 dB then add 1 dB to the higher level to give the total noise level 
                10 dB and over, then the noise level is unchanged (i.e. the higher level is the total level) 
                
                So, 35 dB plus 35 dB equals 38 dB. 
         * 
         */

        // Enrich aircraft with the C02 emission info and calculate total noise
        // in 60secs windows sliding by 30secs.
        StreamStage<KeyedWindowResult<String, Double>> co2Emission = flights
                .map(e -> entry(e.getValue(), getCO2Emission(e.getValue())))
                .setName("Enrich with CO2 info") // (aircraft, co2_emission)
                .window(slidingWindow)
                .groupingKey(entry -> entry.getKey().getairport() + CO2_EMISSION_KEY_SUFFIX)
                .aggregate(summingDouble(Entry::getValue))
                .setName("Calculate avg CO2 level");
        // (airport, total_co2)


        // Build Graphite sink
        Sink<KeyedWindowResult> graphiteSink = buildGraphiteSink(FLIGHT_TELEMETRY_SINK_HOST, SINK_PORT);

        // Drain all results to the Graphite sink
        p.writeTo(graphiteSink, co2Emission, maxNoise, landingFlights, takingOffFlights)
                .setName("graphiteSink");

        return p;
    }

    /**
     * Sink implementation which forwards the items it receives to the Graphite.
     * Graphite's Pickle Protocol is used for communication between Jet and Graphite.
     *
     * @param host Graphite host
     * @param port Graphite port
     */
    private static Sink<KeyedWindowResult> buildGraphiteSink(String host, int port) {
        return sinkBuilder("graphite", instance ->
                new BufferedOutputStream(new Socket(host, port).getOutputStream()))
                .<KeyedWindowResult>receiveFn((bos, entry) -> {
                    GraphiteMetric metric = new GraphiteMetric();
                    metric.from(entry);

                    PyString payload = cPickle.dumps(metric.getAsList(), 2);
                    byte[] header = ByteBuffer.allocate(4).putInt(payload.__len__()).array();

                    bos.write(header);
                    bos.write(payload.toBytes());
                })
                .flushFn(BufferedOutputStream::flush)
                .destroyFn(BufferedOutputStream::close)
                .build();
    }

    /**
     * Returns the average C02 emission on landing/take-off for the aircraft
     *
     * @param aircraft object
     * @return avg C02 for the aircraft
     */
    private static Double getCO2Emission(Aircraft aircraft) {
        // Note aircraft type in the property t (type refers to source of messages)
        return typeToLTOCycyleC02Emission.getOrDefault(aircraft.gett(), 0d);
    }

    /**
     * Returns the noise level at the current altitude of the aircraft
     *
     * @param aircraft object
     * @return noise level of the aircraft
     */
    private static Integer getNoise(Aircraft aircraft) {
        Long altitude = aircraft.getalt();
        SortedMap<Integer, Integer> lookupTable = getPhaseNoiseLookupTable(aircraft);
        if (lookupTable.isEmpty()) {
            return 0;
        }
        return lookupTable.tailMap(altitude.intValue()).values().iterator().next();
    }

    /**
     * Sets the airport field of the aircraft by looking at the coordinates of it
     *
     * @param aircraft object
     */
    private static Aircraft assignAirport(Aircraft aircraft) {
        if (aircraft.getalt() > 0 && !aircraft.isgnd()) {
            String airport = getAirport(aircraft.getlon(), aircraft.getlat());
            if (airport == null) {
                return null;
            }
            aircraft.setairport(airport);
        }
        return aircraft;
    }

    /**
     * Returns if the aircraft is in the defined radius area of the airport.
     *
     * @param lon longitude of the aircraft
     * @param lat latitude of the aircraft
     * @return name of the airport
     */
    private static String getAirport(Double lon, Double lat) {
        if (nearLHR(lon, lat)) {
            return "London Heathrow";
        } else if (nearLCY(lon, lat)) {
            return "London City";
        } else if (nearLGW(lon, lat)) {
            return "London Gatwick";
        } else if (nearEWR(lon, lat)) {
            return "New York Newark";
        } else if (nearJFK(lon, lat)) {
            return "New York JFK";
        } else if (nearLGA(lon, lat)) {
            return "New York LaGuardia";
        } else if (nearHND(lon, lat)) {
            return "Tokyo Haneda";
        } else if (nearNRT(lon, lat)) {
            return "Tokyo Narita";
        }
        // unknown city
        return null;
    }

    /**
     * Returns altitude to noise level lookup table for the aircraft based on its weight category
     *
     * @param aircraft object
     * @return SortedMap contains altitude to noise level mappings.
     */
    private static SortedMap<Integer, Integer> getPhaseNoiseLookupTable(Aircraft aircraft) {
        VerticalDirection verticalDirection = aircraft.getverticaldirection();
        WakeTurbulanceCategory wtc = aircraft.getwtc();
        if (ASCENDING.equals(verticalDirection)) {
            if (HEAVY.equals(wtc)) {
                return heavyWTCClimbingAltitudeToNoiseDb;
            } else {
                return mediumWTCClimbingAltitudeToNoiseDb;
            }
        } else if (DESCENDING.equals(verticalDirection)) {
            if (HEAVY.equals(wtc)) {
                return heavyWTCDescendAltitudeToNoiseDb;
            } else {
                return mediumWTCDescendAltitudeToNoiseDb;
            }
        }
        return emptySortedMap();
    }

    /**
     * Returns the vertical direction based on the linear trend coefficient of the altitude
     *
     * @param coefficient, a positive coefficient indicates an upwards trend (ascending), negative is downwards trend (descending)
     * @return VerticalDirection enum value
     */
    private static VerticalDirection getVerticalDirection(double coefficient) {
        if (coefficient == Double.NaN) {
            return UNKNOWN;
        }
        if (coefficient > 0) {
            return ASCENDING;
        } else if (coefficient == 0) {
            return CRUISE;
        } else {
            return DESCENDING;
        }
    }

    /**
     * Attaches a listener to {@link IMap} which passes added items to the specified consumer
     *
     * @param map      map instance which the listener will be added
     * @param consumer aircraft consumer that the added items will be passed on.
     */
    private static void addListener(IMap<Long, Aircraft> map, Consumer<Aircraft> consumer) {
        map.addEntryListener((EntryAddedListener<Long, Aircraft>) event ->
                consumer.accept(event.getValue()), true);
    }

    /**
     * A data transfer object for Graphite
     */
    private static class GraphiteMetric {
        PyString metricName;
        PyInteger timestamp;
        PyFloat metricValue;

        private GraphiteMetric() {
        }

        private void fromAirCraftEntry(KeyedWindowResult<String, Aircraft> aircraftEntry) {
            Aircraft aircraft = aircraftEntry.getValue();
            metricName = new PyString(replaceWhiteSpace(aircraft.getairport()) + "." + aircraft.getverticaldirection());
            timestamp = new PyInteger(getEpochSecond(aircraft.getpos_time()));
            metricValue = new PyFloat(1);
        }

        private void fromMaxNoiseEntry(KeyedWindowResult<String, Integer> entry) {
            metricName = new PyString(replaceWhiteSpace(entry.getKey()));
            timestamp = new PyInteger(getEpochSecond(entry.end()));
            metricValue = new PyFloat(entry.getValue());
        }

        private void fromTotalC02Entry(KeyedWindowResult<String, Double> entry) {
            metricName = new PyString(replaceWhiteSpace(entry.getKey()));
            timestamp = new PyInteger(getEpochSecond(entry.end()));
            metricValue = new PyFloat(entry.getValue());
        }

        void from(KeyedWindowResult entry) {
            // We need to check which type of entry we are processing (we tagged the noise and co2 map keys with a suffix
            // so that we can easily work this out from the key name)
            if (entry.getKey() instanceof String &&
                    !(
                            entry.getKey().toString().endsWith(CO2_EMISSION_KEY_SUFFIX) ||
                                    entry.getKey().toString().endsWith(AVG_NOISE_KEY_SUFFIX)
                    )
            ) {
                fromAirCraftEntry(entry);
            } else {
                // If the key ends with the CO2 suffix, process as CO2 metric
                if (entry.getKey().toString().endsWith(CO2_EMISSION_KEY_SUFFIX)) {
                    fromTotalC02Entry(entry);
                } else {
                    // This must be a noise entry, process as noise metric
                    fromMaxNoiseEntry(entry);
                }
            }
        }

        PyList getAsList() {
            PyList list = new PyList();
            PyTuple metric = new PyTuple(metricName, new PyTuple(timestamp, metricValue));
            list.add(metric);
            return list;
        }

        private int getEpochSecond(long millis) {
            return (int) Instant.ofEpochMilli(millis).getEpochSecond();
        }

        private String replaceWhiteSpace(String string) {
            return string.replace(" ", "_");
        }
    }
}
