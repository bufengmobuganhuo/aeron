/*
 * Copyright 2014-2021 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.samples.cluster.tutorial;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static java.lang.Integer.parseInt;

/**
 * Node that launches the service for the {@link BasicAuctionClusteredService}.
 */
// tag::new_service[]
public class BasicAuctionClusteredServiceNode
// end::new_service[]
{
    private static ErrorHandler errorHandler(final String context)
    {
        return
            (Throwable throwable) ->
            {
                System.err.println(context);
                throwable.printStackTrace(System.err);
            };
    }

    // tag::ports[]
    private static final int PORT_BASE = 9000;
    private static final int PORTS_PER_NODE = 100;
    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int LOG_CONTROL_PORT_OFFSET = 6;
    private static final int REPLICATION_PORT_OFFSET = 7;
    private static final int TERM_LENGTH = 64 * 1024;

    static int calculatePort(final int nodeId, final int offset)
    {
        return PORT_BASE + (nodeId * PORTS_PER_NODE) + offset;
    }
    // end::ports[]

    // tag::udp_channel[]
    private static String udpChannel(final int nodeId, final String hostname, final int portOffset)
    {
        final int port = calculatePort(nodeId, portOffset);
        return new ChannelUriStringBuilder()
            .media("udp")
            .termLength(TERM_LENGTH)
            .endpoint(hostname + ":" + port)
            .build();
    }
    // end::udp_channel[]

    private static String logControlChannel(final int nodeId, final String hostname, final int portOffset)
    {
        final int port = calculatePort(nodeId, portOffset);
        return new ChannelUriStringBuilder()
            .media("udp")
            .termLength(TERM_LENGTH)
            .controlMode(CommonContext.MDC_CONTROL_MODE_MANUAL)
            .controlEndpoint(hostname + ":" + port)
            .build();
    }

    private static String logReplicationChannel(final String hostname)
    {
        return new ChannelUriStringBuilder()
            .media("udp")
            .endpoint(hostname + ":0")
            .build();
    }

    private static String clusterMembers(final List<String> hostnames)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hostnames.size(); i++)
        {
            sb.append(i);
            sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(i, CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(i, MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(i, LOG_PORT_OFFSET));
            sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(i, TRANSFER_PORT_OFFSET));
            sb.append(',').append(hostnames.get(i)).append(':')
                .append(calculatePort(i, ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }

        return sb.toString();
    }

    /**
     * Main method for launching the process.
     *
     * @param args passed to the process.
     */
    @SuppressWarnings("try")
    // tag::main[]
    public static void main(final String[] args)
    {
        // 为每个节点分配一个ID
        final int nodeId = parseInt(System.getProperty("aeron.cluster.tutorial.nodeId"));               // <1>
        // 为集群定义一个hostname集合
        final String[] hostnames = System.getProperty(
            "aeron.cluster.tutorial.hostnames", "localhost,localhost,localhost").split(",");            // <2>
        final String hostname = hostnames[nodeId];
        // 负责存储raft log，recording log等
        final File baseDir = new File(System.getProperty("user.dir"), "node" + nodeId);                 // <3>
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + nodeId + "-driver";
        // 一个时间屏障，用于阻塞一个或多个线程，直到从操作系统接收到 SIGINT 或 SIGTERM 信号或通过编程调用signal() 。
        // 用于关闭服务。
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();                              // <4>
        // end::main[]

        // tag::media_driver[]
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                // 当前节点的目录，每个节点都有自己的目录，通过nodeId区分
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .terminationHook(barrier::signal)
            .errorHandler(BasicAuctionClusteredServiceNode.errorHandler("Media Driver"));
        // end::media_driver[]

        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                // 使用当前节点的hostname
            .controlResponseChannel("aeron:udp?endpoint=" + hostname + ":0");

        // tag::archive[]
        final Archive.Context archiveContext = new Archive.Context()
                // 同MediaDriver
            .aeronDirectoryName(aeronDirName)
                // 每个节点自己的目录，通过nodeId区分
            .archiveDir(new File(baseDir, "archive"))
                // 使用当前节点的hostname
            .controlChannel(udpChannel(nodeId, hostname, ARCHIVE_CONTROL_PORT_OFFSET))
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED);
        // end::archive[]

        // tag::archive_client[]
        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);
        // end::archive_client[]

        // tag::consensus_module[]
        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .errorHandler(errorHandler("Consensus Module"))
                // 每个ConsensusModule都需要一个唯一标识
            .clusterMemberId(nodeId)                                                                     // <1>
                // 指定集群的所有静态成员以及他们对ConsensusModule的各种操作所需的所有endpoint, 配置格式如下
                // 0,ingress:port,consensus:port,log:port,catchup:port,archive:port|\
                //1,ingress:port,consensus:port,log:port,catchup:port,archive:port|...
            .clusterMembers(clusterMembers(Arrays.asList(hostnames)))                                    // <2>
                // 每个节点有一个自己的数据目录给ConsensusModule用
            .clusterDir(new File(baseDir, "cluster"))                                                    // <3>
                // 这里不需要填写endpoint，因为上面clusterMembers已经有了
            .ingressChannel("aeron:udp?term-length=64k")                                                 // <4>
                // 同样不需要写endpoint，必须使用MDC或多播的channel
            .logChannel(logControlChannel(nodeId, hostname, LOG_CONTROL_PORT_OFFSET))                    // <5>
                // 当需要记日志或进行快照步骤时，这个channel用来接收来自其他节点的复制响应
            .replicationChannel(logReplicationChannel(hostname))                                         // <6>
                // 给ConsensusModule存档
            .archiveContext(aeronArchiveContext.clone());                                                // <7>
        // end::consensus_module[]

        // tag::clustered_service[]
        final ClusteredServiceContainer.Context clusteredServiceContext =
            new ClusteredServiceContainer.Context()
                    // 这里配置的是当前节点的目录
            .aeronDirectoryName(aeronDirName)                                                            // <1>
            .archiveContext(aeronArchiveContext.clone())                                                 // <2>
                    // 这里配置的是当前节点的目录
            .clusterDir(new File(baseDir, "cluster"))
                    // 将业务逻辑的实例绑定到集群
            .clusteredService(new BasicAuctionClusteredService())                                        // <3>
            .errorHandler(errorHandler("Clustered Service"));
        // end::clustered_service[]

        // tag::running[]
        try (
            ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);                             // <1>
            // 应用程序包含在这里
            ClusteredServiceContainer container = ClusteredServiceContainer.launch(
                clusteredServiceContext))                                                                // <2>
        {
            System.out.println("[" + nodeId + "] Started Cluster Node on " + hostname + "...");
            // 等待集群停止信号
            barrier.await();                                                                             // <3>
            System.out.println("[" + nodeId + "] Exiting");
        }
        // end::running[]
    }
}
