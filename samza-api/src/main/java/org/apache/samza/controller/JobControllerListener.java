package org.apache.samza.controller;

import java.util.List;
import java.util.Map;

public interface JobControllerListener {
    void changePartitionAssignment(Map<String, List<String>> partitionAssignment);
    void scaling(int parallelism, Map<String, List<String>> partitionAssignment);
}
