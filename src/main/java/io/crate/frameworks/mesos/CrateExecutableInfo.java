package io.crate.frameworks.mesos;


import com.google.common.base.Joiner;
import io.crate.frameworks.mesos.config.Configuration;
import io.crate.frameworks.mesos.config.Resources;
import org.apache.mesos.Protos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static io.crate.frameworks.mesos.SaneProtos.taskID;
import static java.util.Arrays.asList;

public class CrateExecutableInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrateExecutableInfo.class);
    private final static String CDN_URL = "https://cdn.crate.io/downloads/releases";
    private final static String CMD = "crate";

    public final static int TRANSPORT_PORT = 4300;

    private final Collection<String> occupiedHosts;
    private final String clusterName;

    private final String hostname;
    private final CommandInfo.URI downloadURI;
    private final TaskID taskId;
    private final String nodeNode;
    private final Configuration configuration;

    public CrateExecutableInfo(Configuration configuration,
                               String hostname,
                               Collection<String> occupiedHosts) {
        this.configuration = configuration;
        this.occupiedHosts = occupiedHosts;
        this.taskId = generateTaskId();
        this.clusterName = configuration.clusterName();
        this.hostname = hostname;
        this.downloadURI = CommandInfo.URI.newBuilder()
                .setValue(String.format("%s/crate-%s.tar.gz", CDN_URL, configuration.version()))
                .setExtract(true)
                .build();
        this.nodeNode = String.format("%s-%s", this.clusterName, taskId.getValue());
    }

    private TaskID generateTaskId() {
        return taskID(UUID.randomUUID().toString());
    }

    public String getHostname() {
        return hostname;
    }

    private String unicastHosts() {
        List<String> hosts = new ArrayList<>();
        for (String occupiedHost : occupiedHosts) {
            hosts.add(String.format("%s:%s", occupiedHost, TRANSPORT_PORT));
        }
        return Joiner.on(",").join(hosts);
    }

    public TaskInfo taskInfo(Offer offer) {
        assert Resources.matches(offer.getResourcesList(), configuration) :
                "must have enough resources in offer. Otherwise CrateContainer must not be created";

        Environment env = Environment.newBuilder()
                .addAllVariables(Arrays.<Environment.Variable>asList(
                        Environment.Variable.newBuilder()
                                .setName("CRATE_HEAP_SIZE")
                                .setValue(String.format("%sm", configuration.resourcesHeap().longValue()))
                                .build()
                ))
                .build();


        List<String> args = asList(
                String.format("-Des.cluster.name=%s", clusterName),
                String.format("-Des.http.port=%d", configuration.httpPort()),
                String.format("-Des.node.name=%s", nodeNode),
                String.format("-Des.discovery.zen.ping.multicast.enabled=%s", "false"),
                String.format("-Des.discovery.zen.ping.unicast.hosts=%s", unicastHosts())
        );
        String command = String.format("cd crate-* && bin/crate %s", Joiner.on(" ").join(args));
        LOGGER.debug("Launch Crate with command: {}", command);

        // command info
        CommandInfo cmd = CommandInfo.newBuilder()
                .addAllUris(asList(downloadURI))
                .setShell(true)
                .setEnvironment(env)
                .setValue(command)
                .build();

        // create task to run
        TaskInfo.Builder taskBuilder = TaskInfo.newBuilder()
                .setName(clusterName)
                .setTaskId(taskId)
                .setSlaveId(offer.getSlaveId())
                .setCommand(cmd);

        taskBuilder.addAllResources(configuration.getAllRequiredResources());
        return taskBuilder.build();
    }
}