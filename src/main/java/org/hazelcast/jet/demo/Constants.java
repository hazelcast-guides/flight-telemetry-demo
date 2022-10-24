package org.hazelcast.jet.demo;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Contains look-up values for C02 emission and Noise Level calculation.
 *
 * There are two wake turbulence categories (medium-heavy) and both have
 * different ascending and descending noise levels. In total there are 4
 * different lookup tables for noise levels at altitudes.
 *
 * For C02 emissions, there is a lookup table which contains typical C02
 * emissions for a landing/take-off(LTO) cycle for the most common aircraft types.
 *
 */
class Constants {

    static final SortedMap<Integer, Integer> mediumWTCDescendAltitudeToNoiseDb = new TreeMap<Integer, Integer>() {{
        put(4000, 50);
        put(3000, 63);
        put(2000, 71);
        put(1000, 86);
        put(500, 93);
    }};
    static final SortedMap<Integer, Integer> heavyWTCDescendAltitudeToNoiseDb = new TreeMap<Integer, Integer>() {{
        put(4000, 57);
        put(3000, 70);
        put(2000, 79);
        put(1000, 94);
        put(500, 100);
    }};
    static final SortedMap<Integer, Integer> heavyWTCClimbingAltitudeToNoiseDb = new TreeMap<Integer, Integer>() {{
        put(500, 96);
        put(1000, 92);
        put(1500, 86);
        put(2000, 82);
        put(3000, 72);
        put(4000, 59);
    }};

    static final SortedMap<Integer, Integer> mediumWTCClimbingAltitudeToNoiseDb = new TreeMap<Integer, Integer>() {{
        put(500, 83);
        put(1000, 81);
        put(1500, 74);
        put(2000, 68);
        put(3000, 61);
        put(4000, 48);
    }};


    static final Map<String, Double> typeToLTOCycyleC02Emission = new HashMap<String, Double>() {{
        /*
        * Figures for CO2 emmissions are taken from the 2019 EMEP/EEA air pollutant emission inventory guidebook (2019)
        *
        *   Link:  https://www.eea.europa.eu/publications/emep-eea-guidebook-2019/part-b-sectoral-guidance-chapters/1-energy/1-a-combustion/1-a-3-a-aviation
        *
        * Older aircraft no longer listed but still appearing below are assumed correct at the time of writing.
        *
        */
        put("A306", 5427.89d);
        put("A310", 4821.24d);
        put("A318", 2169d); 
        put("A319", 2169.76d);
        put("A320", 2570.93d);
        put("A321", 2560d);
        put("A332", 6829.44d);
        put("A333", 6829.44d);
        put("A343", 6362.65);
        put("A345", 10329.23);
        put("A346", 10624.82d);
        put("A359", 6026d);
        put("A380", 13048.56);
        put("A388", 13048d);
        put("A388", 13048d);
        put("B735", 2305d);
        put("B737", 2597.65d);
        put("B738", 2775.47d);
        put("B739", 2500d);
        put("B742", 9684.89d);
        put("B743",9684.89d);
        put("B744",10456.98d);
        put("B748", 10400d);
        put("B752", 4292.19d);
        put("B753", 4610.47d);
        put("B762", 4607.37d);
        put("B763", 5449.29d);
        put("B772", 7580.19d);
        put("B773", 8072.95d);
        put("B77L", 9076d);
        put("B77W", 9736.15d);
        put("B788", 10944.46d);
        put("B789", 10300d);
        put("CRJ9", 2754d);
        put("CRJ2", 1560d);
        put("CRJ7", 2010d);
        put("DC10", 7460d);
        put("DC8", 5339.85d);
        put("DH8D", 1950d);
        put("E145", 1505d);
        put("E170", 1516.91d);
        put("E175", 1516.91d);
        put("E190", 1605d);
        put("F27", 684.03d);
        put("MD11", 8277.92d);
        put("T39", 578.6d);
    }};

}
