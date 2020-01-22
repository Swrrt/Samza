package org.apache.samza.controller.streamswitch;

import javafx.util.Pair;
import org.apache.samza.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

//Under development

public class DelayGuaranteeStreamSwitch extends StreamSwitch {
    private static final Logger LOG = LoggerFactory.getLogger(DelayGuaranteeStreamSwitch.class);

    long migrationWarmupTime, migrationInterval, lastTime;
    double instantaneousThreshold, longTermThreshold;
    Examiner examiner;
    public DelayGuaranteeStreamSwitch(Config config){
        super(config);
        migrationWarmupTime = config.getLong("streamswitch.migration.warmup.time", 1000000000l);
        migrationInterval = config.getLong("streamswitch.migration.interval.time", 5000l);
        instantaneousThreshold = config.getDouble("streamswitch.delay.instant.threshold", 400.0);
        longTermThreshold = config.getDouble("streamswtich.delay.longterm.threshold", 400.0);
        lastTime = -1000000000l;
        algorithms = new Algorithms();
        updateLock = new ReentrantLock();
        examiner = new Examiner();
        examiner.model.setState(examiner.state);
        examiner.model.setTimes(config.getLong("streamswitch.delay.interval", 500l), config.getInt("streamswitch.delay.alpha", 20), config.getInt("streamswitch.delay.beta", 10));
    }

    class Prescription {
        String source, target;
        List<String> migratingPartitions;
        Prescription(){
            migratingPartitions = null;
        }
        Prescription(String source, String target, List<String> migratingPartitions){
            this.source = source;
            this.target = target;
            this.migratingPartitions = migratingPartitions;
        }
        Map<String, List<String>> generateNewPartitionAssignment(Map<String, List<String>> oldAssignment){
            Map<String, List<String>> newAssignment = new HashMap<>();
            for(String executor: oldAssignment.keySet()){
                List<String> partitions = new ArrayList<>(oldAssignment.get(executor));
                newAssignment.put(executor, partitions);
            }
            if (!newAssignment.containsKey(target)) newAssignment.put(target, new LinkedList<>());
            for (String partition : migratingPartitions) {
                newAssignment.get(source).remove(partition);
                newAssignment.get(target).add(partition);
            }
            //For scale in
            if (newAssignment.get(source).size() == 0) newAssignment.remove(source);
            return newAssignment;
        }

    }


    //Algorithms packed
    protected class Algorithms {
        // Find the subset which minimizes delay by greedy:
        // Sort arrival rate from largest to smallest, cut them in somewhere
        public Pair<Prescription, List<Pair<String, Double>>> tryToScaleOut(){
            LOG.info("Scale out by one container");
            if(partitionAssignment.size() <= 0){
                LOG.info("No executor to move");
                return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
            }
            Pair<String, Double> a = findMaxLongtermDelayExecutor(partitionAssignment);
            String srcExecutor = a.getKey();
            double initialDelay = a.getValue();
            if(srcExecutor == null || srcExecutor.equals("") || partitionAssignment.get(srcExecutor).size() <=1){
                LOG.info("Cannot scale out: insufficient partition to migrate");
                return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
            }

            LOG.info("Migrating out from executor " + srcExecutor);

            //Try from lightest arrival to heaviest arrival
            PriorityQueue<Pair<String, Double>> priorityQueue = new PriorityQueue<>((x,y)-> {
                if(x.getValue() - 1e-9 > y.getValue())return 1;
                if(y.getValue() - 1e-9 > x.getValue())return -1;
                return x.getKey().compareTo(y.getKey());
            });
            for(String partition: partitionAssignment.get(srcExecutor)){
                priorityQueue.add(new Pair(partition, examiner.model.calculatePartitionInstantDelay(partition, examiner.state.currentTimeIndex)));
            }
            //Debugging
            for(Pair<String, Double> x: priorityQueue){
                LOG.info("Partition " + x.getKey() + " delay=" + x.getValue());
            }

            double arrivalrate0 = examiner.model.executorArrivalRate.get(srcExecutor), arrivalrate1 = 0;
            double serviceRate = examiner.model.serviceRate.get(srcExecutor); //Assume new executor has same service rate
            double best = 1e100;
            List<String> migratingPartitions = new ArrayList<>();
            while(priorityQueue.size() > 1){
                Pair<String, Double> t = priorityQueue.poll();
                double arrival = examiner.model.partitionArrivalRate.get(t.getKey());
                arrivalrate0 -= arrival;
                arrivalrate1 += arrival;
                LOG.info("Debugging: current partition " + t.getKey() + " arrival rate=" + t.getValue() + ", src=" + arrivalrate0 + ", tgt=" + arrivalrate1);
                if(arrivalrate0 < serviceRate && arrivalrate1 < serviceRate) {
                    double delay0 = estimateLongtermDelay(arrivalrate0, serviceRate), delay1 = estimateLongtermDelay(arrivalrate1, serviceRate);
                    if (delay0 < best && delay1 < best) {
                        best = Math.max(delay0, delay1);
                        migratingPartitions.add(t.getKey());
                    }else break;
                }
                if(arrivalrate1 > serviceRate)break;
            }

            long newExecutorId = getNextExecutorID();
            String tgtExecutor = String.format("%06d", newExecutorId);
            setNextExecutorId(newExecutorId + 1);
            LOG.info("Debugging, scaling out migrating partitions: " + migratingPartitions);
            return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(srcExecutor, tgtExecutor, migratingPartitions), null);
        }

        //Compare two delay vector, assume they are sorted
        public boolean vectorGreaterThan(PriorityQueue<Pair<String, Double>> v1, PriorityQueue<Pair<String, Double>> v2){
            Iterator<Pair<String,Double>> i1 = v1.iterator(), i2 = v2.iterator();
            while(i1.hasNext()){
                double t1 = i1.next().getValue(), t2 = i2.next().getValue();
                if(t1 - 1e-9 > t2)return true;
                if(t2 - 1e-9 > t1)return false;
            }
            i1 = v1.iterator();
            i2 = v2.iterator();
            while(i1.hasNext()) {
                int t = i1.next().getKey().compareTo(i2.next().getKey());
                if(t > 0)return true;
                if(t < 0)return false;
            }
            return false;
        }
        //Iterate all pairs of source and targe OE, find the one minimize delay vector
        public Pair<Prescription, List<Pair<String, Double>>> tryToScaleIn(){
            LOG.info("Try to scale in");
            if(partitionAssignment.size() <= 1){
                LOG.info("Not enough executor to merge");
                return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
            }

            String minsrc = "", mintgt = "";
            PriorityQueue<Pair<String, Double>> best = null;

            for(String src: partitionAssignment.keySet()){
                double srcArrival = examiner.model.executorArrivalRate.get(src);
                for(String tgt: partitionAssignment.keySet())
                    if(!src.equals(tgt)){
                        double tgtArrival = examiner.model.executorArrivalRate.get(tgt);
                        double tgtService = examiner.model.serviceRate.get(tgt);
                        if(srcArrival + tgtArrival < tgtService){
                            double estimatedLongtermDelay = estimateLongtermDelay(srcArrival + tgtArrival, tgtService);
                            PriorityQueue<Pair<String, Double>> current = new PriorityQueue<>((x,y)-> {
                                if(x.getValue() - 1e-9 > y.getValue())return 1;
                                if(y.getValue() - 1e-9 > x.getValue())return -1;
                                return x.getKey().compareTo(y.getKey());
                            });
                            for(String executor: partitionAssignment.keySet()){
                                if(executor.equals(src)){
                                    current.add(new Pair(executor, 0.0));
                                }else if(executor.equals(tgt)){
                                    current.add(new Pair(executor, estimatedLongtermDelay));
                                }else{
                                    current.add(new Pair(executor, examiner.model.getLongTermDelay(executor)));
                                }
                            }

                            if(best == null || vectorGreaterThan(best, current)){
                                best = current;
                                minsrc = src;
                                mintgt = tgt;
                            }
                        }
                    }
            }


            if(best != null){
                List<String> migratingPartitions = new ArrayList<>(partitionAssignment.get(minsrc));
                LOG.info("Scale in! from " + minsrc + " to " + mintgt);
                LOG.info("Migrating partitions: " + migratingPartitions);
                return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(minsrc, mintgt, migratingPartitions), new ArrayList(best));
            }
            LOG.info("Cannot find any scale in");
            return new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
        }


        private long getNextExecutorID(){
            return nextExecutorID.get();
        }

        private void setNextExecutorId(long id){
            if(id > nextExecutorID.get()){
                nextExecutorID.set(id);
            }
        }

        private Pair<String, Double> findMaxLongtermDelayExecutor(Map<String, List<String>> partitionAssignment){
            double initialDelay = -1.0;
            String maxExecutor = "";
            for (String executor : partitionAssignment.keySet()) {
                double longtermDelay = examiner.model.getLongTermDelay(executor);
                if (longtermDelay > initialDelay) {
                    initialDelay = longtermDelay;
                    maxExecutor = executor;
                }
            }
            return new Pair(maxExecutor, initialDelay);
        }

        public double estimateLongtermDelay(double arrivalRate, double serviceRate) {
            if(serviceRate < arrivalRate + 1e-15)return 1e100;
            return 1.0/(serviceRate - arrivalRate);
        }

        public Pair<Prescription, List<Pair<String, Double>>> tryToMigrate(){
            LOG.info("Try to migrate");
            LOG.info("Migrating once based on assignment: " + partitionAssignment);
            if (partitionAssignment.size() == 0) { //No executor to move
                Pair<Prescription, List<Pair<String, Double>>> result = new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
                return result;
            }

            //Find container with maximum delay
            Pair<String, Double> a = findMaxLongtermDelayExecutor(partitionAssignment);
            String srcExecutor = a.getKey();
            double initialDelay = a.getValue();
            if (srcExecutor.equals("")) { //No correct container
                LOG.info("Cannot find the container that exceeds threshold");
                Pair<Prescription, List<Pair<String, Double>>> result = new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
                return result;
            }

            if (partitionAssignment.get(srcExecutor).size() <= 1) { //Container has only one partition
                LOG.info("Largest delay container " + srcExecutor + " has only " + partitionAssignment.get(srcExecutor).size());
                Pair<Prescription, List<Pair<String, Double>>> result = new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
                return result;
            }
            LOG.info("Try to migrate from largest delay container " + srcExecutor);
            String bestTgtExecutor = null;
            PriorityQueue<Pair<String, Double>> best = null;
            List<String> bestMigratingPartitions = null;
            for (String tgtExecutor : partitionAssignment.keySet())
                if (!srcExecutor.equals(tgtExecutor)) {
                    double tgtArrivalRate = examiner.model.executorArrivalRate.get(tgtExecutor);
                    double tgtServiceRate = examiner.model.serviceRate.get(tgtExecutor);
                    if (tgtArrivalRate < tgtServiceRate - 1e-9) {
                        PriorityQueue<Pair<String, Double>> partitions = new PriorityQueue<>((x,y)-> {
                            if(x.getValue() - 1e-9 > y.getValue())return -1;
                            if(y.getValue() - 1e-9 > x.getValue())return 1;
                            return x.getKey().compareTo(y.getKey());
                        });

                        for(String partition: partitionAssignment.get(srcExecutor)){
                            partitions.add(new Pair(partition, examiner.model.calculatePartitionInstantDelay(partition, examiner.state.currentTimeIndex)));
                        }
                        //Debugging
                        for(Pair<String, Double> x:partitions){
                            LOG.info("Partition " + x.getKey() + " delay=" + x.getValue());
                        }

                        double srcArrivalRate = examiner.model.executorArrivalRate.get(srcExecutor);
                        double srcServiceRate = examiner.model.serviceRate.get(srcExecutor);
                        List<String> migrating = new ArrayList<>();
                        LOG.info("Debugging, try to migrate to " + tgtExecutor + "tgt la=" + tgtArrivalRate + "tgt mu=" + tgtServiceRate);
                        while(partitions.size() > 1){ //Cannot migrate all partitions out?
                            Pair<String, Double> t = partitions.poll();
                            double arrival = examiner.model.partitionArrivalRate.get(t.getKey());
                            srcArrivalRate -= arrival;
                            tgtArrivalRate += arrival;
                            migrating.add(t.getKey());
                            if(srcArrivalRate < srcServiceRate && tgtArrivalRate < tgtServiceRate){
                                double srcDelay = estimateLongtermDelay(srcArrivalRate, srcServiceRate), tgtDelay = estimateLongtermDelay(tgtArrivalRate, tgtServiceRate);
                                LOG.info("Debugging, current src la=" + srcArrivalRate + " tgt la=" + tgtArrivalRate + " partitions=" + migrating);
                                PriorityQueue<Pair<String, Double>> current = new PriorityQueue<>((x,y)-> {
                                    if(x.getValue() - 1e-9 > y.getValue())return -1;
                                    if(y.getValue() - 1e-9 > x.getValue())return 1;
                                    return x.getKey().compareTo(y.getKey());
                                });
                                for(String executor: partitionAssignment.keySet()){
                                    if(executor.equals(srcExecutor)){
                                        current.add(new Pair(executor, srcDelay));
                                    }else if(executor.equals(tgtExecutor)){
                                        current.add(new Pair(executor, tgtDelay));
                                    }else{
                                        current.add(new Pair(executor, examiner.model.getLongTermDelay(executor)));
                                    }
                                }
                                LOG.info("Debugging, vectors=" + current);
                                if(best == null || vectorGreaterThan(best, current)){
                                    best = current;
                                    bestTgtExecutor = tgtExecutor;
                                    bestMigratingPartitions = migrating;
                                }
                                if(tgtDelay > srcDelay)break;
                            }
                            if(tgtArrivalRate > tgtServiceRate)break;
                        }
                    }
                }
            if(best == null){
                LOG.info("Cannot find any migration");
                Pair<Prescription, List<Pair<String, Double>>> result = new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(), null);
                return result;
            }
            LOG.info("Find best migration with delay: " + best + ", from executor " + srcExecutor + " to executor " + bestTgtExecutor + " migrating Partitions: " + bestMigratingPartitions);
            Pair<Prescription, List<Pair<String, Double>>> result = new Pair<Prescription, List<Pair<String, Double>>>(new Prescription(srcExecutor, bestTgtExecutor, bestMigratingPartitions), new ArrayList<>(best));
            return result;
        }
    }

    class Examiner{
        class State {
            Map<String, Map<Long, Long>> partitionArrived, partitionCompleted; //Instead of actual time, use the n-th time point as key
            Map<String, Double> executorUtilization;
            Map<Long, Long> timePoints; //Actual time of n-th time point.
            Map<String, Long> partitionLastValid; //Last valid state time point
            long beginTimeIndex;
            long currentTimeIndex;
            long storedTimeWindowSize;
            public State() {
                timePoints = new HashMap<>();
                currentTimeIndex = -1;
                beginTimeIndex = 0;
                storedTimeWindowSize = 100;
                partitionArrived = new HashMap<>();
                partitionCompleted = new HashMap<>();
                executorUtilization = new HashMap<>();
                partitionLastValid = new HashMap<>();
            }

            protected long getTimepoint(long n){
                //Debugging
                if(!timePoints.containsKey(n)){
                    LOG.error("Time not contains index " + n);
                }

                return timePoints.get(n);
            }
            public void updatePartitionArrived(String partitionId, long n, long arrived){
                partitionArrived.putIfAbsent(partitionId, new TreeMap<>());
                partitionArrived.get(partitionId).put(n, arrived);
            }
            public void updatePartitionCompleted(String partitionId, long n, long completed){
                partitionCompleted.putIfAbsent(partitionId, new TreeMap<>());
                partitionCompleted.get(partitionId).put(n, completed);
            }
            public void updateUtilization(String executorId, double utilization){
                executorUtilization.put(executorId, utilization);
            }
            public long getPartitionArrived(String partitionId, long n){
                long arrived = 0;
                if(partitionArrived.containsKey(partitionId)){
                    arrived = partitionArrived.get(partitionId).getOrDefault(n, 0l);
                }
                return arrived;
            }
            public long getPartitionCompleted(String partitionId, long n){
                long completed = 0;
                if(partitionCompleted.containsKey(partitionId)){
                    completed = partitionCompleted.get(partitionId).getOrDefault(n, 0l);
                }
                return completed;
            }
            //Return -1 for no utilization
            public double getUtilization(String executorId){
                return executorUtilization.getOrDefault(executorId, -1.0);
            }
            /*
            Remove old data that is useless:
                  For all partition, Arrived(i) < Processed(n-1)-1?
                  and i < n - windowSize
            */
            private void dropOldState(){
                LOG.info("Try to drop old states");
                while(beginTimeIndex < currentTimeIndex - storedTimeWindowSize){
                    for(String partition: partitionArrived.keySet()){
                        long arrived = partitionArrived.get(partition).get(beginTimeIndex + 2);
                        long processed = partitionCompleted.get(partition).get(currentTimeIndex - 2);
                        if(!(arrived + 1 < processed - 1)){
                            LOG.info("Time " +beginTimeIndex+ " is still useful for " + partition + ", cannot drop");
                            return ; //If beginIndex is useful, stop pop
                        }
                        if(partitionLastValid.get(partition) <= beginTimeIndex){
                            LOG.info("Time " +beginTimeIndex+ " is the last valid state for " + partition + ", cannot drop");
                            return ;
                        }
                    }

                    LOG.info("Dropping old state at time " + beginTimeIndex);
                    for(String partition: partitionArrived.keySet()){
                        partitionArrived.get(partition).remove(beginTimeIndex);
                        partitionCompleted.get(partition).remove(beginTimeIndex);
                    }
                    timePoints.remove(beginTimeIndex);
                    beginTimeIndex++;
                }
            }
            public long calculateArrivalTime(String partition, long r){
                for(Map.Entry<Long, Long> entry: partitionArrived.get(partition).entrySet()){
                    if(entry.getKey() > state.beginTimeIndex && r <= entry.getValue() && r > partitionArrived.get(partition).get(entry.getKey() - 1))return entry.getKey();
                }
                return 0;
            }

            //Only called when time n is valid, also update partitionLastValid
            public void calibratePartitionState(String partition, long n){
                long a1 = state.getPartitionArrived(partition, n);
                long c1 = state.getPartitionCompleted(partition, n);
                long n0 = partitionLastValid.get(partition);
                LOG.info("Calibrate state for " + partition + " from time=" + n0);
                long a0 = state.getPartitionArrived(partition, n0);
                long c0 = state.getPartitionCompleted(partition, n0);
                for(long i = n0 + 1; i < n; i++){
                    long ai = (a1 - a0) * i / (n - n0);
                    long ci = (c1 - c0) * i / (n - n0);
                    state.partitionArrived.get(partition).put(i, ai);
                    state.partitionCompleted.get(partition).put(i, ci);
                }
                partitionLastValid.put(partition, n);
            }

            public void updateAtTime(long time, Map<String, Long> taskArrived, Map<String, Long> taskProcessed, Map<String, Double> utilization, Map<String, List<String>> partitionAssignment) { //Normal update
                LOG.info("Debugging, metrics retrieved data, time: " + time + " taskArrived: "+ taskArrived + " taskProcessed: "+ taskProcessed + " assignment: " + partitionAssignment);
                currentTimeIndex++;
                if(currentTimeIndex == 0){ //Initialize
                    LOG.info("Initialize time point 0...");
                    for(String executor: partitionAssignment.keySet()){
                        for(String partition: partitionAssignment.get(executor)){
                            updatePartitionCompleted(partition, 0, 0);
                            updatePartitionArrived(partition, 0, 0);
                            partitionLastValid.put(partition, 0l);
                        }
                    }
                    timePoints.put(0l, 0l);
                    currentTimeIndex++;
                }
                timePoints.put(currentTimeIndex, time);
                LOG.info("Current time " + time);
                for(String partition: taskArrived.keySet()){
                    updatePartitionArrived(partition, currentTimeIndex, taskArrived.get(partition));
                }
                for(String partition: taskProcessed.keySet()){
                    updatePartitionCompleted(partition, currentTimeIndex, taskProcessed.get(partition));
                }
                for(String executor: utilization.keySet()){
                    updateUtilization(executor, utilization.get(executor));
                }

            }
            private void writeLog(String string){
                System.out.println("DelayEstimator: " + string);
            }
        }
        //Cannot use actually use window to store information, because we need to calibrate state.
        class Model {
            private State state;
            Map<String, Double> partitionArrivalRate, executorArrivalRate, serviceRate, executorInstantaneousDelay;
            private Map<Long, Map<String, List<String>>> windowedMappings;
            private Map<String, Deque<Pair<Long, Double>>> utilizationWindow;
            private int alpha = 1, beta = 2;
            public Model(){
                partitionArrivalRate = new HashMap<>();
                executorArrivalRate = new HashMap<>();
                serviceRate = new HashMap<>();
                executorInstantaneousDelay = new HashMap<>();
                windowedMappings = new HashMap<>();
                utilizationWindow = new HashMap<>();
            }
            public void setTimes(long interval, int a, int b){
                alpha = a;
                beta = b;
            }
            public void setState(State state){
                this.state = state;
            }


            //Calculate instant delay for tuples (c(n-1), c(n)]
            private double calculatePartitionInstantDelay(String partition, long n){
                if(n <= 0)return 0;
                //No completed in n-1 ~ n
                if(state.getPartitionCompleted(partition, n - 1) == state.getPartitionCompleted(partition, n)){
                    return 0;
                }
                /*long ct_1 = state.getPartitionCompleted(partition, n - 1), ct = state.getPartitionCompleted(partition, n);
                long xt_1 = state.calculateArrivalTime(partition, ct_1 + 1), xt = state.calculateArrivalTime(partition, ct);
                long l = 0;
                if(xt_1 != xt){

                }else{
                    l = n - xt + 1;
                }*/
                long cn = state.getPartitionCompleted(partition, n), cn_1 = state.getPartitionCompleted(partition, n - 1);
                //m(c(n-1)+ 1), m(c(n))
                long m0 = state.calculateArrivalTime(partition, cn_1 + 1), m1 = state.calculateArrivalTime(partition, cn);
                long l = 0;
                LOG.info("Debugging, partition " + partition + " n=" + n + " cn=" + cn + " cn_1=" + cn_1 + " m0=" + m0 + " m1=" + m1);
                if(m0 != m1) {
                    long a0 = state.getPartitionArrived(partition, m0 - 1), a1 = state.getPartitionArrived(partition, m0),
                            a2 = state.getPartitionArrived(partition, m1 - 1), a3 = state.getPartitionArrived(partition, m1);
                    long M = (a1 - cn_1) * m0 - (a3 - cn) * m1;
                    long aa0 = state.calculateArrivalTime(partition, cn_1) + 1;
                    if(aa0 <= m0)aa0 = m0 + 1;
                    for (long m = aa0; m <= m1; m++) {
                        long am = state.getPartitionArrived(partition, m), am_1 = state.getPartitionArrived(partition, m - 1);
                        M += (am - am_1) * m;
                    }
                    l = (n + 1 - M / (cn - cn_1));
                    LOG.info("a1=" + a1 + " a3=" + a3 + " aa0=" + aa0 + " M=" + M );
                }else l = n - m1 + 1;
                long T = examiner.timeSlotSize;
                long delay =  l * T;
                LOG.info("Debugging, l="+ l + " delay=" + delay);
                return delay;
            }

            private double calculateExecutorInstantDelay(String executor, long n){
                double totalDelay = 0;
                long totalCompleted = 0;
                for(String partition: partitionAssignment.get(executor)){
                    long completed = state.getPartitionCompleted(partition, n) - state.getPartitionCompleted(partition, n - 1);
                    double delay = calculatePartitionInstantDelay(partition, n);
                    totalDelay += delay * completed;
                    LOG.info("Debugging, partition " + partition + " delay: " + delay + " processed: " + completed);
                    totalCompleted += completed;
                }
                double delay = 0;
                if(totalCompleted > 0)delay = totalDelay / totalCompleted;
                return delay;
            }


            // 1 / ( u - n ). Return  1e100 if u <= n
            private double getLongTermDelay(String executorId){
                double arrival = executorArrivalRate.get(executorId);
                double service = serviceRate.get(executorId);
                if(arrival < 1e-15)return 0.0;
                if(service < arrival + 1e-15)return 1e100;
                return 1.0/(service - arrival);
            }

            private double getPartitionArrivalRate(String partition, long n0, long n1){
                long totalArrived = 0;
                if(n0 < 0)n0 = 0;
                double time = state.getTimepoint(n1) - state.getTimepoint(n0);
                totalArrived = state.getPartitionArrived(partition, n1) - state.getPartitionArrived(partition, n0);
                double arrivalRate = 0;
                if(time > 1e-9)arrivalRate = totalArrived / time;
                return arrivalRate;
            }

            // Calculate window service rate of n - beta ~ n (exclude n - beta)
            private double getExecutorServiceRate(String executorId, long n){
                long totalServiced = 0;
                long totalTime = 0;
                long n0 = n - beta + 1;
                if(n0<1)n0 = 1;
                for(long i = n0; i <= n; i++){
                    long time = state.getTimepoint(i) - state.getTimepoint(i-1);
                    totalTime += time;
                    for(String partition: windowedMappings.get(i).get(executorId)) {
                        totalServiced += state.getPartitionCompleted(partition, i) - state.getPartitionCompleted(partition, i-1);
                    }
                }
                if(totalTime > 0) return totalServiced/((double)totalTime);
                return 0;
            }

            private void updateWindowExecutorUtilization(String executor, long n){
                double util = state.getUtilization(executor);
                if(!utilizationWindow.containsKey(executor)){
                    utilizationWindow.put(executor, new LinkedList<>());
                }
                utilizationWindow.get(executor).addLast(new Pair(n, util));
                if(utilizationWindow.get(executor).size() > beta){
                    utilizationWindow.get(executor).pollFirst();
                }
            }

            // Window average utilization
            private double getWindowExecutorUtilization(String executorId){
                double totalUtilization = 0;
                long totalTime = 0;
                for(Pair<Long, Double> entry: utilizationWindow.get(executorId)){
                    long time = state.getTimepoint(entry.getKey()) - state.getTimepoint(entry.getKey() - 1);
                    totalTime += time;
                    totalUtilization += entry.getValue() * time;
                }
                if(totalTime > 0) totalUtilization/= totalTime;
                return totalUtilization;
            }
            //Window average delay
            public double getWindowExecutorInstantaneousDelay(String executorId, long n){
                double totalDelay = 0;
                long totalProcessed = 0;
                long n0 = n - beta + 1;
                if(n0<1)n0 = 1;
                for(long i = n0; i <= n; i++){
                    long processed = 0;
                    for(String partition: windowedMappings.get(i).get(executorId)){
                        processed += state.getPartitionCompleted(partition, i) - state.getPartitionCompleted(partition, i - 1);
                    }
                    totalProcessed += processed;
                    totalDelay += calculateExecutorInstantDelay(executorId, i) * processed;
                }
                if(totalProcessed > 0) totalDelay/=totalProcessed;
                return totalDelay;
            }

            //Update snapshots from state
            public void updateModelSnapshot(long n, Map<String, List<String>> partitionAssignment){
                LOG.info("Updating model snapshot, clear old data...");
                partitionArrivalRate.clear();
                executorArrivalRate.clear();
                serviceRate.clear();
                executorInstantaneousDelay.clear();
                windowedMappings.put(n, partitionAssignment);
                if(windowedMappings.containsKey(n - beta - 2))windowedMappings.remove(n - beta - 2);
                for(String executor: partitionAssignment.keySet()){
                    double arrivalRate = 0;
                    for(String partition: partitionAssignment.get(executor)){
                        double t = getPartitionArrivalRate(partition, n - beta, n);
                        partitionArrivalRate.put(partition, t);
                        arrivalRate += t;
                    }
                    executorArrivalRate.put(executor, arrivalRate);
                    updateWindowExecutorUtilization(executor, n);
                    double util = getWindowExecutorUtilization(executor);
                    double mu = getExecutorServiceRate(executor, n);
                    if(util > 1e-9 && util <= 1){
                        mu /= util;
                    }
                    serviceRate.put(executor, mu);
                    executorInstantaneousDelay.put(executor, getWindowExecutorInstantaneousDelay(executor, n));
                }

                LOG.info("Debugging, executor utilization window: " + utilizationWindow);
                LOG.info("Debugging, executor windowed delay: " + executorInstantaneousDelay);
                LOG.info("Debugging, executor windowed service: " + serviceRate);
            }
            public void dropOldData(long time){

            }

            public void showData(){
                LOG.info("Show delay estimation data...");
                LOG.info("Partition arrival rate:");
            }
        }
        private Model model;
        private State state;
        private StreamSwitchMetricsRetriever metricsRetriever;
        private boolean isValid, isMigrating;
        private Prescription pendingPres;
        private long timeSlotSize;
        private long lastDeployed;
        Examiner(){
            this.state = new State();
            this.model = new Model();
            isValid = false;//No data, should be false
            timeSlotSize = 200l; //Default
            lastDeployed = 0l;
        }
        public void setMetricsRetriever(StreamSwitchMetricsRetriever metricsRetriever){
            this.metricsRetriever = metricsRetriever;
        }
        public void setTimeSlotSize(long size){
            timeSlotSize = size;
        }
        private boolean checkMetricsValid(Map<String, Object> metrics){
            boolean isValid = true;
            if(!metrics.containsKey("PartitionValid"))isValid = false;
            Map<String, Boolean> partitionValid = (HashMap<String,Boolean>)metrics.get("PartitionValid");
            for(String executor: partitionAssignment.keySet())
                for(String partition: partitionAssignment.get(executor)) {
                    if (!partitionValid.containsKey(partition) || !partitionValid.get(partition)) {
                        LOG.info(partition + "'s metrics is not valid");
                        isValid = false;
                    }else{
                        state.calibratePartitionState(partition, state.currentTimeIndex);
                    }
                }
            return isValid;
        }
        private void examine(long time){
            Map<String, Object> metrics = metricsRetriever.retrieveMetrics();
            Map<String, Long> partitionArrived =
                    (HashMap<String, Long>) (metrics.get("PartitionArrived"));
            Map<String, Long> partitionProcessed =
                    (HashMap<String, Long>) (metrics.get("PartitionProcessed"));
            Map<String, Double> executorUtilization =
                    (HashMap<String, Double>) (metrics.get("ExecutorUtilization"));
            isValid = true;
            updateState(time, partitionArrived, partitionProcessed, executorUtilization);
            updateModel();
            if(!checkMetricsValid(metrics))isValid = false;
            for(String executor: partitionAssignment.keySet()){
                if(!model.executorArrivalRate.containsKey(executor)){
                    LOG.info("Current model is not valid, because " + executor + " is not ready.");
                    isValid = false;
                    break;
                }
                if(!executorUtilization.containsKey(executor)){
                    LOG.info("Current state is not valid, because " + executor + " utilization is missing");
                    isValid = false;
                    break;
                }
                for(String partition: partitionAssignment.get(executor)){
                    if(!partitionArrived.containsKey(partition)){
                        LOG.info("Current state is not valid, because " + partition + " arrived is missing");
                        isValid = false;
                        break;
                    }
                    if(!partitionProcessed.containsKey(partition)){
                        LOG.info("Current state is not valid, because " + executor + " processed is missing");
                        isValid = false;
                        break;
                    }
                }
                if(!isValid)break;
            }
        }
        private void updateState(long time, Map<String, Long> partitionArrived, Map<String, Long> partitionProcessed, Map<String, Double> executorUtilization){
            LOG.info("Updating network calculus model...");
            state.updateAtTime(time, partitionArrived, partitionProcessed, executorUtilization, partitionAssignment);
            state.dropOldState();
            //Debug & Statistics
            HashMap<String, Long> arrived = new HashMap<>(), completed = new HashMap<>();
            for(String partition: state.partitionArrived.keySet()) {
                arrived.put(partition, state.partitionArrived.get(partition).get(state.currentTimeIndex));
                completed.put(partition, state.partitionCompleted.get(partition).get(state.currentTimeIndex));
            }
            System.out.println("State, time " + time  + " , Partition Arrived: " + arrived);
            System.out.println("State, time " + time  + " , Partition Completed: " + completed);
        }

        private void updateModel(){
            LOG.info("Updating Delay Estimating model");
            model.updateModelSnapshot(state.currentTimeIndex, partitionAssignment);

            //Debug & Statistics
            if(true){
                HashMap<String, Double> longtermDelay = new HashMap<>();
                for(String executorId: partitionAssignment.keySet()){
                    double delay = model.getLongTermDelay(executorId);
                    longtermDelay.put(executorId, delay);
                }
                long time = state.getTimepoint(state.currentTimeIndex);
                System.out.println("Model, time " + time  + " , Arrival Rate: " + model.executorArrivalRate);
                System.out.println("Model, time " + time  + " , Service Rate: " + model.serviceRate);
                System.out.println("Model, time " + time  + " , Instantaneous Delay: " + model.executorInstantaneousDelay);
                System.out.println("Model, time " + time  + " , Longterm Delay: " + longtermDelay);
                System.out.println("Model, time " + time  + " , Partition Arrival Rate: " + model.partitionArrivalRate);
            }
        }
        private Map<String, Double> getInstantDelay(){
            return examiner.model.executorInstantaneousDelay;
        }

        private List<Pair<String, Double>> getLongtermDelay(){
            List<Pair<String, Double>> delay = new LinkedList<>();
            for(String executor: partitionAssignment.keySet()){
                delay.add(new Pair(executor,model.getLongTermDelay(executor)));
            }
            return delay;
        }

        private Pair<Prescription, List<Pair<String, Double>>> loadBalance(){
            return algorithms.tryToMigrate();
        }
        private Pair<Prescription, List<Pair<String, Double>>> scaleIn(){
            return algorithms.tryToScaleIn();
        }
        private Pair<Prescription, List<Pair<String, Double>>> scaleOut(){
            return algorithms.tryToScaleOut();
        }
    }

    Algorithms algorithms;


    //We do not use this method anymore in paper's version
    @Override
    protected boolean updateModel(long time, Map<String, Object> metrics) {
        return false;
    }
    //Check healthisness of input delay vectors: 0 for Good, 1 for Moderate, 2 for Severe
    private int checkHealthiness(Map<String, Double> instantDelay, List<Pair<String, Double>> longtermDelay){
        int instantExceeded = 0;
        int longtermExceeded = 0;
        int both = 0;
        for(Map.Entry<String, Double> entry: instantDelay.entrySet()){
            if(entry.getValue() > instantaneousThreshold)instantExceeded = 1;
        }
        for(Pair<String, Double> entry: longtermDelay){
            if(entry.getValue() > longTermThreshold){
                longtermExceeded = 1;
                if(instantDelay.get(entry.getKey()) > instantaneousThreshold)both = 1;
            }
        }
        if(both == 1)return 2;
        if(instantExceeded > 0 || longtermExceeded > 0)return 1;
        return 0;
    }
    private Prescription diagnose(Examiner examiner){
        int healthiness = checkHealthiness(examiner.getInstantDelay(), examiner.getLongtermDelay());
        Prescription pres = new Prescription(null, null, null);
        LOG.info("Debugging, instant delay vector: " + examiner.getInstantDelay() + " long term delay vector: " + examiner.getLongtermDelay());
        if(examiner.isMigrating){
            LOG.info("Migration does not complete, cannot diagnose");
            return pres;
        }

        //Moderate
        if(healthiness == 1){
            LOG.info("Current healthiness is Moderate, do nothing");
            return pres;
        }

        //Good
        if(healthiness == 0){
            LOG.info("Current healthiness is Good");
            //Try scale in
            /*Pair<Prescription, List<Pair<String, Double>>> result = examiner.scaleIn();
            if(result.getValue() != null) {
                int thealthiness = checkHealthiness(examiner.getInstantDelay(), result.getValue());
                if (thealthiness == 0) {  //Scale in OK
                    return result.getKey();
                }
            }*/
            //Do nothing
            return pres;
        }
        //Severe
        else{
            LOG.info("Current healthiness is Servere");
            Pair<Prescription, List<Pair<String, Double>>> result = examiner.loadBalance();
            //LOG.info("The result of load-balance: " + result.getValue());
            if(result.getValue() != null) {
                int thealthiness = checkHealthiness(examiner.getInstantDelay(), result.getValue());
                //LOG.info("If we migrating, healthiness is " + thealthiness);
                if (thealthiness == 1) {  //Load balance OK
                    LOG.info("Load-balance is OK");
                    return result.getKey();
                }
            }
            //Scale out
            LOG.info("Cannot load-balance, need to scale out");
            result = examiner.scaleOut();
            return result.getKey();
        }
    }
    //Treatment for Samza
    private void treat(Prescription pres){
        if(pres.migratingPartitions == null){
            LOG.warn("Prescription has nothing, so do no treatment");
            return ;
        }

        examiner.pendingPres = pres;
        examiner.isMigrating = true;

        LOG.info("Old mapping: " + partitionAssignment);
        Map<String, List<String>> newAssignment = pres.generateNewPartitionAssignment(partitionAssignment);
        LOG.info("Old mapping: " + partitionAssignment);
        LOG.info("Prescription : src: " + pres.source + " , tgt: " + pres.target + " , migrating: " + pres.migratingPartitions);
        LOG.info("New mapping: " + newAssignment);
        //Scale out
        if (!partitionAssignment.containsKey(pres.target)) {
            LOG.info("Scale out");
            //For drawing figure
            System.out.println("Migration! Scale out prescription at time: " + examiner.state.getTimepoint(examiner.state.currentTimeIndex) + " from executor " + pres.source + " to executor " + pres.target);

            listener.scaling(newAssignment.size(), newAssignment);
        }
        //Scale in
        else if(partitionAssignment.get(pres.source).size() == pres.migratingPartitions.size()) {
            LOG.info("Scale in");
            //For drawing figure
            System.out.println("Migration! Scale in prescription at time: " + examiner.state.getTimepoint(examiner.state.currentTimeIndex) + " from executor " + pres.source + " to executor " + pres.target);

            listener.scaling(newAssignment.size(), newAssignment);
        }
        //Load balance
        else {
            LOG.info("Load balance");
            //For drawing figure
            System.out.println("Migration! Load balance prescription at time: " + examiner.state.getTimepoint(examiner.state.currentTimeIndex) + " from executor " + pres.source + " to executor " + pres.target);

            listener.changePartitionAssignment(newAssignment);
        }
    }
    //Main logic:  examine->diagnose->treatment->sleep
    @Override
    public void start(){
        int metricsRetreiveInterval = config.getInt("streamswitch.metrics.interval", 200);
        int metricsWarmupTime = config.getInt("streamswitch.metrics.warmup.time", 60000);
        startTime = System.currentTimeMillis();
        examiner.setMetricsRetriever(retriever);
        examiner.setTimeSlotSize(metricsRetreiveInterval);
        //Warm up phase
        LOG.info("Warm up for " + metricsWarmupTime + " milliseconds...");
        do{
            long time = System.currentTimeMillis();
            if(time - startTime > metricsWarmupTime){
                break;
            }
            else {
                try{
                    Thread.sleep(500l);
                }catch (Exception e){
                    LOG.error("Exception happens during warming up, ", e);
                }
            }
        }while(true);
        LOG.info("Warm up completed.");
        while(true) {
            //Examine
            LOG.info("Examine...");
            long time = System.currentTimeMillis();
            examiner.examine(time);
            LOG.info("Diagnose...");
            if(examiner.isValid && !examiner.isMigrating && time - examiner.lastDeployed > migrationInterval) {
                Prescription pres = diagnose(examiner);
                if (pres.migratingPartitions != null) { //Not do nothing
                    treat(pres);
                } else {
                    LOG.info("Nothing to do this time.");
                }
            }else{
                if(!examiner.isValid)LOG.info("Current examine data is not valid, need to wait until valid");
                else if(examiner.isMigrating)LOG.info("One migration is in process");
                else LOG.info("Too close to last migration");
            }
            long deltaT = System.currentTimeMillis() - time;
            if(deltaT > metricsRetreiveInterval){
                LOG.warn("Run loop time (" + deltaT + ") is longer than interval(" + metricsRetreiveInterval + "), please consider to set larger interval");
                LOG.info("No sleeping this time ");
            }else {
                LOG.info("Sleep for " + (metricsRetreiveInterval - deltaT) + "milliseconds");
                try {
                    Thread.sleep(metricsRetreiveInterval - deltaT);
                } catch (Exception e) {
                    LOG.warn("Exception happens between run loop interval, ", e);
                }
            }
        }
    }
    //TODO
    @Override
    public synchronized void onChangeImplemented(){
        LOG.info("Migration actually deployed, try to acquire lock...");
        updateLock.lock();
        try {
            LOG.info("Lock acquired, set migrating flag to false");
            if (examiner == null) {
                LOG.warn("Examiner haven't been initialized");
            } else if (!examiner.isMigrating) {
                LOG.warn("There is no pending migration, please checkout");
            } else {
                examiner.isMigrating = false;
                examiner.lastDeployed = System.currentTimeMillis();
                Prescription pres = examiner.pendingPres;
                LOG.info("Migrating " + pres.migratingPartitions + " from " + pres.source + " to " + pres.target);
                //For drawing figre
                System.out.println("Change implemented at time " + System.currentTimeMillis() + " :  from " + pres.source + " to " + pres.target);

                //Scale in, remove useless information
                if(pres.migratingPartitions.size() == partitionAssignment.get(pres.source).size()){
                    examiner.model.utilizationWindow.remove(pres.source);
                }

                partitionAssignment = pres.generateNewPartitionAssignment(partitionAssignment);
                examiner.pendingPres = null;
            }
        }finally {
            updateLock.unlock();
            LOG.info("Migration complete, unlock");
        }
    }
}
