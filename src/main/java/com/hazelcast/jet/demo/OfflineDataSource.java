package com.hazelcast.jet.demo;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.internal.json.Json;
import com.hazelcast.internal.json.JsonArray;
import com.hazelcast.internal.json.JsonObject;
import com.hazelcast.jet.demo.util.Util;
import com.hazelcast.jet.pipeline.SourceBuilder;
import com.hazelcast.jet.pipeline.SourceBuilder.TimestampedSourceBuffer;
import com.hazelcast.jet.pipeline.StreamSource;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.hazelcast.internal.util.StringUtil.isNullOrEmpty;

public class OfflineDataSource implements IFlightDataSource {

    public static final String OFFLINE_DATA_MAP_SUFFIX = "_OfflineData";
    private final long pollIntervalMillis;

    // holds a list of known aircraft, with last seen
    private final Map<String, Long> aircraftLastSeenAt = new HashMap<>();

    private final ILogger logger;

    private long initialTimeDifference = 0L;

    private long lastPollEpoch;
    private long currentEpoch;

    private AtomicLong minEpoch = new AtomicLong();

    private final String cityName;

    private OfflineDataSource(ILogger logger, String cityName, long pollIntervalMillis) {

        this.logger = logger;
        this.pollIntervalMillis = pollIntervalMillis;
        this.cityName = cityName;

        minEpoch.set(System.currentTimeMillis());

        loadData();
    }

    public void fillBuffer(TimestampedSourceBuffer<Aircraft> buffer) throws IOException {

        currentEpoch = System.currentTimeMillis();

        if (lastPollEpoch == 0L) {
            lastPollEpoch = currentEpoch;
            initialTimeDifference = currentEpoch - minEpoch.get();
            logger.fine("The time difference between now and the minimum epoch loaded is [" + initialTimeDifference + "] where now is [" + currentEpoch + "] and min epoch loaded is [ " + minEpoch.get() + "]");
        }

        // If the last poll was more than pollIntervalMillis ago then poll again
        if ((lastPollEpoch + pollIntervalMillis) < currentEpoch) {

            JsonObject response = pollForAircraft();

            lastPollEpoch = currentEpoch;

            // Compared to the FlightDataSource there is no need to filer by reg number (as the offline data
            // is already filtered), by last seen (as each line in the offline data is a new position) nor do we need
            // to calculate posTime from the timestamp in the API response however we do need to adjust based on the
            // time difference between the offline data and when the data was loaded
            if (response != null) {
                JsonArray aircraftList = response.get("ac").asArray();
                aircraftList.values().stream()
                        .map(IFlightDataSource::parseAircraft)
                        .filter(a -> !isNullOrEmpty(a.getr())) // there should be a reg number
                        .filter(a -> a.getpos_time() + initialTimeDifference > aircraftLastSeenAt.getOrDefault(a.getid(), 0L))
                        .forEach(a -> {
                            long positionTime = a.getpos_time() + initialTimeDifference;

                            // Let's update the aircraft timestamp fields so it looks like LIVE data
                            a.setpos_time(positionTime);
                            a.setnow(a.getnow() + initialTimeDifference);

                            aircraftLastSeenAt.put(a.getid(), positionTime);
                            buffer.add(a, positionTime);
                        });

                logger.info("Polled " + aircraftList.size() + " aircraft, " + buffer.size() + " new positions.");
            } else {
                logger.info("Poll for aircraft failed.");
            }
        }
    }

    public JsonObject pollForAircraft() throws IOException {

        HazelcastInstance hzInstance = Util.getHazelcastInstance();
        IMap<String, HazelcastJsonValue> offlineDataMap = hzInstance.getMap(this.cityName + OFFLINE_DATA_MAP_SUFFIX);

        logger.finest("[" + cityName + "] now > " + (lastPollEpoch - initialTimeDifference) + " AND now <= " + (currentEpoch - initialTimeDifference));
        Predicate predicate = Predicates.sql("now > " + (lastPollEpoch - initialTimeDifference) + " AND now < " + (currentEpoch - initialTimeDifference));

        Collection<HazelcastJsonValue> ac = offlineDataMap.values(predicate);

        JsonArray acJsonArray = new JsonArray();

        ac.stream().forEach(e -> {
            acJsonArray.add(Json.parse(e.toString()));
        });

        JsonObject object = new JsonObject();
        object.add("ac", acJsonArray);

        return object;
    }

    public static StreamSource<Aircraft> getDataSource(String url, HazelcastInstance hazelcastInstance, String cityName, long pollIntervalMillis) {

        return SourceBuilder.timestampedStream("Flight Data Source",
                        ctx -> new OfflineDataSource(ctx.logger(), cityName, pollIntervalMillis))
                .fillBufferFn(OfflineDataSource::fillBuffer)
                .build();
    }

    private void loadData() {

        HazelcastInstance hzInstance = Util.getHazelcastInstance();
        IMap<Long, HazelcastJsonValue> offlineDataMap = hzInstance.getMap(this.cityName + OFFLINE_DATA_MAP_SUFFIX);

        long dataLoadStartEpoch = System.currentTimeMillis();
        AtomicLong entryCount = new AtomicLong(0L);

        try (
                Stream<Path> entries = Files.walk(Paths.get(Util.getFlightDataLogPath(this.cityName)))
                        .filter(Files::isRegularFile)
        ) {
            entries.forEach(file -> {
                        try {
                            // Skip hidden files
                            if (!Files.isHidden(file)) {
                                logger.finest("Loading from file [" + file + "]");
                                try (Stream<String> lines = Files.lines(file)) {
                                    lines.forEach(line -> {
                                        JsonObject object = Json.parse(line).asObject();

                                        long updateEpoch = object.get("now").asLong();

                                        // If the position epoch looks valid then and is lower than minEpoch then update the minEpoch
                                        if (updateEpoch > 0 && (updateEpoch < minEpoch.get()) || minEpoch.get() == 0) {
                                            minEpoch.accumulateAndGet(updateEpoch, Math::min);
                                        }

                                        offlineDataMap.put(entryCount.get(), new HazelcastJsonValue(line));

                                        entryCount.getAndIncrement();

                                    });
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        } catch (IOException e) {
                            logger.fine("Encountered IOException checking if file [" + file + "] is hidden.");
                        }
                    }
            );

        } catch (Exception e) {
            logger.severe("Error loading data . A total of [" + entryCount.get() + "] flight positions were loaded for [" + cityName + "] in " + (System.currentTimeMillis() - dataLoadStartEpoch) + "ms");
            System.out.println(e.toString());
            e.printStackTrace();
        }

        logger.info("Successfully loaded offline data. A total of [" + entryCount.get() + "] flight positions were loaded for [" + cityName + "] in " + (System.currentTimeMillis() - dataLoadStartEpoch) + "ms");
    }
}
